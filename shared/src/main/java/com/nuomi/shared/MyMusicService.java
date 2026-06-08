package com.nuomi.shared;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyMusicService extends MediaBrowserServiceCompat {

    private MediaSessionCompat mSession;                       // 本地 MediaSession
    private MediaControllerCompat remoteCtrl;                  // 指向外部播放器的控制器（QQ 或 NCM）
    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback(); // 监听状态变化

    private static final String CUSTOM_ACTION_SHOW_LYRICS = "com.nuomi.SHOW_LYRICS";
    private static final String CUSTOM_ACTION_REPEAT_MODE = "com.nuomi.REPEAT_MODE";

    private static final String ACTION_CONTROLLER = "com.nuomi.ACTION_CONTROLLER";
    private static final String ACTION_REQUEST_TOKEN = "com.nuomi.REQUEST_TOKEN";
    private static final String ACTION_TOGGLE_LYRICS_MODE = "com.nuomi.ACTION_TOGGLE_LYRICS_MODE";
    public static final String ACTION_KEEP_ALIVE_START = "com.nuomi.KEEP_ALIVE_START";
    public static final String ACTION_KEEP_ALIVE_STOP = "com.nuomi.KEEP_ALIVE_STOP";
    public static final String SETTING_KEEP_ALIVE = "keepForegroundAlive";

    private static final String PKG_QQMUSIC = "com.tencent.qqmusic";
    private static final String QQ_ACTION_PLAY_MODE_WIDGET =
            "com.tencent.qqmusic.ACTION_SERVICE_PLAY_MODE_WIDGET.QQMusicPhone";
    private static final String META_KEY_PLAY_MODE = "ucar.media.metadata.PLAY_MODE";
    private static final String META_KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE";

    private static final String SP_SESSION = "session_pref";
    private static final String SP_LAST_PKG = "last_pkg";
    private static final String SP_LAST_META = "last_meta";
    private static final String SP_SETTINGS = "settings";

    private static final long STANDARD_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_PLAY_PAUSE;

    private List<Pair<Long, String>> parsedLyrics = new ArrayList<>();
    private boolean isLyricsMode = false; // 仅 QQ 模式可用；NCM 模式强制关闭
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastPlayMode = 2; // QQ 播放模式缓存：默认 repeat-all（避免初始展示成 shuffle）
    private int lastLyricsIdx = -1; // 最近一次歌词行号，用于跨 tick 去重 setX 调用

    // ===== 仅在"QQ 歌词模式"下使用的缓存/本地时钟 =====
    private MediaMetadataCompat lastRemoteMeta = null;
    private PlaybackStateCompat lastRemoteState = null;

    private boolean suppressRemoteState = false; // 拖动后的保护期：忽略短期旧状态回写
    private final Runnable clearSuppression = () -> suppressRemoteState = false;

    private long basePosMs = 0L;
    private long baseUpdateElapsed = 0L;
    private float baseSpeed = 0f;
    private int baseState = PlaybackStateCompat.STATE_NONE;
    private long durationMs = 0L;

    private String lastLyricsRaw = null;

    // 当前是否处于"网易云模式"（false=QQ 模式；true=非 QQ）
    private boolean isNcmMode = false;

    // 用来在后台 bind 目标 App 的 MediaBrowserService 并触发 play()。
    private MediaBrowserCompat autoStartBrowser;
    private int autoStartAttempts = 0;

    // 路径 B PoC：在 autoStart 连接成功后，旁路构造一个 MediaController 注册回调，
    // 观察直接从 MediaBrowserService 拿的 SessionToken 是否能收到 metadata / state。
    // 用于评估是否可去掉 NotificationListener 主路径。日志-only，不改主流程行为。
    private static final String PATH_B_TAG = "PathB";
    private static final long PATH_B_OBSERVE_DURATION_MS = 12_000L;
    private MediaControllerCompat probeCtrl;
    private MediaControllerCompat.Callback probeCb;
    private boolean probeSawMetadata = false;
    private boolean probeSawState = false;

    private static final String TAG = "Mirror";

    // mobile 模块里的 Sniffer 组件名（shared 不能直接引用 mobile 类，硬编码字符串避免循环依赖）
    private static final String SNIFFER_CLASS = "com.nuomi.MusicSessionSniffer";
    private static final String MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService";
    private static final int AUTO_START_MAX_ATTEMPTS = 3;
    private static final long AUTO_START_RETRY_DELAY_MS = 2000L;

    // 前台 Service 相关：在 AA bind 期间显示一个 MIN 优先级通知，让进程在 AA 重连之间保持存活。
    private static final String FG_CHANNEL_ID = "nuomi.aa.fg";
    private static final int FG_NOTIFICATION_ID = 1042;
    private int boundClientCount = 0;
    private boolean inForeground = false;
    // keep-alive：用户手动开启后，service 一直前台，不随 AA bind/unbind 变化。
    private boolean keepAliveEnabled = false;

    // ===== 发现循环（AA bind 后用来持续请求 token） =====
    // AA bind 与目标 App / NLS 绑定可能有数十秒延迟。
    // 用 60s 自适应循环，tick 间逐步退避（0.5s → 8s 上限），
    // 拿到 remoteCtrl 立刻停。onGetRoot / onLoadChildren 会刷新窗口起点。
    private long discoveryStartElapsed = 0L;
    private long discoveryNextDelay = 0L;
    private boolean discoveryRunning = false;
    private int discoveryTickCount = 0;
    private static final long DISCOVERY_WINDOW_MS = 60_000L;
    private static final long DISCOVERY_INITIAL_DELAY_MS = 500L;
    private static final long DISCOVERY_MAX_DELAY_MS = 8_000L;
    // 只在前 3 个 tick 主动叫 NLS 重绑：rebind 只对"NLS 还没连上"有效，
    // Sniffer 已经活着的情况下持续 rebind 是纯浪费 IPC + 日志噪音。
    private static final int DISCOVERY_REBIND_TICK_LIMIT = 3;

    private final Runnable discoveryTick = new Runnable() {
        @Override public void run() {
            if (remoteCtrl != null) { stopDiscovery("got-token"); return; }
            long elapsed = SystemClock.elapsedRealtime() - discoveryStartElapsed;
            if (elapsed > DISCOVERY_WINDOW_MS) { stopDiscovery("timeout"); return; }

            discoveryTickCount++;
            LocalBroadcastManager.getInstance(MyMusicService.this)
                    .sendBroadcast(new Intent(ACTION_REQUEST_TOKEN));
            if (discoveryTickCount <= DISCOVERY_REBIND_TICK_LIMIT) {
                requestSnifferRebind();
            }

            // 自适应退避：0.5 → 1 → 2 → 4 → 8s 后封顶
            discoveryNextDelay = Math.min(DISCOVERY_MAX_DELAY_MS,
                    Math.max(DISCOVERY_INITIAL_DELAY_MS, discoveryNextDelay * 2));
            handler.postDelayed(this, discoveryNextDelay);
        }
    };

    private void startDiscovery() {
        if (discoveryRunning) {
            // 已在跑：只刷新窗口起点；不要重置 nextDelay/tickCount，否则连续 onLoadChildren
            // 会把退避退到 500ms 高频轮询。
            discoveryStartElapsed = SystemClock.elapsedRealtime();
            return;
        }
        discoveryStartElapsed = SystemClock.elapsedRealtime();
        discoveryNextDelay = DISCOVERY_INITIAL_DELAY_MS;
        discoveryTickCount = 0;
        discoveryRunning = true;
        Log.i(TAG, "🚦 启动发现循环（60s 窗口）");
        handler.post(discoveryTick);
    }

    private void stopDiscovery(String reason) {
        if (!discoveryRunning) return;
        discoveryRunning = false;
        handler.removeCallbacks(discoveryTick);
        Log.i(TAG, "🛑 停止发现循环 reason=" + reason + " ticks=" + discoveryTickCount);
    }

    private void requestSnifferRebind() {
        try {
            ComponentName cn = new ComponentName(getPackageName(), SNIFFER_CLASS);
            NotificationListenerService.requestRebind(cn);
            Log.i(TAG, "🛎 已请求重绑 Sniffer: " + cn.flattenToShortString());
        } catch (Throwable t) {
            // 用户未授权 / 组件不存在（automotive 变体）/ 任何系统异常都不应让服务崩溃
            Log.w(TAG, "requestRebind 失败: " + t.getMessage());
        }
    }

    private void updateSessionActive(String reason) {
        // session 只要 service 在运行就保持 active，让 AA 始终能发现此应用。
        // AA 通过 metadata/playbackState 内容判断是否有可用媒体，不依赖 isActive() 来决定显示。
        // 原条件 (!isNcmMode && isLyricsMode) 导致 session 几乎永远 inactive，AA 看不见 app。
        if (!mSession.isActive()) {
            mSession.setActive(true);
            Log.i(TAG, "setActive=true reason=" + reason);
        }
    }

    private PlaybackStateCompat buildMinimalState(int state, long pos, float speed) {
        return new PlaybackStateCompat.Builder()
                .setState(state, pos, speed, SystemClock.elapsedRealtime())
                .setActions(STANDARD_ACTIONS)
                .build();
    }

    private int resolveRepeatIcon(int playMode) {
        switch (playMode) {
            case 1: return R.drawable.ic_repeat_one_24dp;
            case 0: return R.drawable.ic_shuffle_24dp;
            case 2:
            default: return R.drawable.ic_repeat_24dp;
        }
    }

    /** 给 PlaybackState builder 追加 QQ 模式的"歌词 + 循环"两个自定义按钮。 */
    private void addQqCustomActions(PlaybackStateCompat.Builder builder, int playMode) {
        int lyricsIconRes = isLyricsMode ? R.drawable.ic_lyrics_24dp : R.drawable.ic_lyrics_outline_24dp;
        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_SHOW_LYRICS, "歌词", lyricsIconRes).build());
        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_REPEAT_MODE, "循环", resolveRepeatIcon(playMode)).build());
    }

    /**
     * 乐观更新 session 的 PlaybackState：用户按下 play/pause 后立刻反映新状态，
     * 不等 remoteCtrl 的真实回调（通常有 100~300ms 延迟）。真实状态到达后会被
     * RemoteCallback 校正覆盖。歌词模式下还要同步 base 时钟，否则下一帧 overlay
     * 会用旧 baseState 渲染。
     */
    private void optimisticPlaybackState(int state) {
        float speed = state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0f;
        if (!isNcmMode && isLyricsMode) {
            baseState = state;
            baseSpeed = speed;
            baseUpdateElapsed = SystemClock.elapsedRealtime();
            lastLyricsIdx = -1;
            applyLyricsOverlay(lastRemoteMeta);
            return;
        }
        PlaybackStateCompat current = mSession.getController().getPlaybackState();
        long pos = (current != null) ? current.getPosition() : 0L;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setState(state, pos, speed, SystemClock.elapsedRealtime())
                .setActions(STANDARD_ACTIONS);
        if (!isNcmMode) addQqCustomActions(b, lastPlayMode);
        mSession.setPlaybackState(b.build());
    }

    /** 用 lastRemoteState 把"基准位置/速度/状态/时钟"快照对齐，供 clockPosition() 推算。 */
    private void snapshotBaseFromRemote() {
        if (lastRemoteState == null) return;
        basePosMs = lastRemoteState.getPosition();
        baseSpeed = lastRemoteState.getPlaybackSpeed();
        baseState = lastRemoteState.getState();
        baseUpdateElapsed = SystemClock.elapsedRealtime();
    }

    /** 开启 QQ 歌词模式：拍快照、起定时器、立刻贴一帧覆盖。 */
    private void enterLyricsMode() {
        isLyricsMode = true;
        lastLyricsIdx = -1; // 重置缓存：进入歌词模式后第一次 overlay 必须真正写入
        snapshotBaseFromRemote();
        handler.post(lyricsUpdater);
        if (remoteCtrl != null) {
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
        }
    }

    /** 关闭歌词模式：停定时器、清拖动保护期。 */
    private void exitLyricsMode() {
        isLyricsMode = false;
        lastLyricsIdx = -1;
        handler.removeCallbacks(lyricsUpdater);
        suppressRemoteState = false;
    }

    /**
     * 给 AA 一个非空的初始 metadata + PAUSED 状态。
     * 冷启动 / 数据清除 / Sniffer 未连上时，避免 session 处于"空 metadata + NONE"
     * 让 AA 立刻显示"无法获享媒体内容"。
     */
    private void seedInitialSessionContent() {
        SharedPreferences lastMeta = getSharedPreferences(SP_LAST_META, MODE_PRIVATE);
        String savedTitle = lastMeta.getString("title", null);
        String savedArtist = lastMeta.getString("artist", "");
        if (savedTitle == null) {
            savedTitle = "糯米播放器";
            savedArtist = "等待音乐源…";
        }
        mSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, savedTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, savedArtist)
                .build());
        mSession.setPlaybackState(buildMinimalState(
                PlaybackStateCompat.STATE_PAUSED, 0, 0f));
        Log.i(TAG, "🗃 初始 metadata: " + savedTitle);
    }



    // 以"基准位置+基准时间+速度"推算当前 position（只在 QQ 歌词模式用）
    private long clockPosition() {
        if (baseState == PlaybackStateCompat.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - baseUpdateElapsed;
            long pos = basePosMs + (long) (elapsed * baseSpeed);
            return Math.max(0L, durationMs > 0 ? Math.min(pos, durationMs) : pos);
        } else {
            return basePosMs;
        }
    }

    // 每秒刷新（仅 QQ 歌词模式）
    private final Runnable lyricsUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isNcmMode && isLyricsMode && remoteCtrl != null) {
                applyLyricsOverlay(lastRemoteMeta);
                handler.postDelayed(this, 1000);
            }
        }
    };

    // "自动开启歌词模式"广播，仅 QQ 模式生效
    private final BroadcastReceiver autoLyricsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "📨 收到自动开启歌词模式请求");
            if (isNcmMode) {
                Log.i(TAG, "ℹ️ 非 QQ 模式，忽略歌词请求");
                return;
            }
            if (!isLyricsMode) {
                Log.i(TAG, "🎵 已开启歌词模式（QQ）");
                enterLyricsMode();
            }
        }
    };


    // =========================================================
    // 🔁 外部播放器回调：将元数据 / 播放状态同步给本地 Session
    // =========================================================
    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override public void onMetadataChanged(MediaMetadataCompat m) {
            lastRemoteMeta = m; // 缓存给 QQ 歌词模式
            mirror(m, null);
        }

        @Override public void onPlaybackStateChanged(PlaybackStateCompat s) {
            lastRemoteState = s; // 缓存给 QQ 歌词模式
            mirror(null, s);
        }
    }

    // =========================================================
    // 🪞 同步信息到本地 Session（按当前模式分支）
    // =========================================================
    private void mirror(MediaMetadataCompat meta, PlaybackStateCompat st) {
        boolean lyricsBranch = !isNcmMode && isLyricsMode;
        if (lyricsBranch) {
            // 一并处理：先把 PlaybackState 的最新值打进 base 时钟，再让 applyLyricsOverlay
            // 一次性 set metadata + playback state（避免 mirrorMetadata + mirrorPlaybackState
            // 在 lyrics 模式下双写 PlaybackState）。
            if (st != null && !suppressRemoteState) {
                basePosMs = st.getPosition();
                baseSpeed = st.getPlaybackSpeed();
                baseState = st.getState();
                baseUpdateElapsed = SystemClock.elapsedRealtime();
            }
            applyLyricsOverlay(meta != null ? meta : lastRemoteMeta);
            return;
        }
        if (meta != null) mirrorMetadataStandard(meta);
        if (st != null) mirrorPlaybackStateStandard(meta, st);
    }

    private void mirrorMetadataStandard(MediaMetadataCompat meta) {
        String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String artist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        persistLastMeta(title, artist);

        if (!isNcmMode) {
            lastPlayMode = (int) meta.getLong(META_KEY_PLAY_MODE);
        }

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        long duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        if (duration > 0) builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        Bitmap art = pickAlbumArt(meta);
        if (art != null) builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        mSession.setMetadata(builder.build());
    }

    private void mirrorPlaybackStateStandard(MediaMetadataCompat meta, PlaybackStateCompat st) {
        int code = st.getState();
        // STATE_NONE/STOPPED 直接跳过：避免 AA 跳回浏览页 & 减少无意义写入
        if (code == PlaybackStateCompat.STATE_NONE || code == PlaybackStateCompat.STATE_STOPPED) return;

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setState(code, st.getPosition(), st.getPlaybackSpeed())
                .setActions(STANDARD_ACTIONS);
        if (!isNcmMode) {
            int playMode = (meta != null) ? (int) meta.getLong(META_KEY_PLAY_MODE) : lastPlayMode;
            addQqCustomActions(builder, playMode);
        }
        mSession.setPlaybackState(builder.build());
    }

    /** 二分查找：找到最大的 i 使 parsedLyrics[i].first <= t；找不到返回 -1。 */
    private int findLyricsIndex(long t) {
        int lo = 0, hi = parsedLyrics.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (parsedLyrics.get(mid).first <= t) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    // 仅在"QQ 歌词模式"调用：把当前/下一句覆盖到元数据
    private void applyLyricsOverlay(MediaMetadataCompat meta) {
        if (isNcmMode || !isLyricsMode || meta == null) return;

        lastPlayMode = (int) meta.getLong(META_KEY_PLAY_MODE);
        long dur = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        if (dur > 0) durationMs = dur;

        String lyricsWhole = meta.getString(META_KEY_LYRICS_WHOLE);
        boolean lyricsChanged = false;
        if (lyricsWhole != null && !lyricsWhole.equals(lastLyricsRaw)) {
            lastLyricsRaw = lyricsWhole;
            parseLyrics(lyricsWhole);
            lyricsChanged = true; // 换歌：必须重写一次 metadata 让 AA 拿到新封面/时长
        }

        int idx = parsedLyrics.isEmpty() ? -1 : findLyricsIndex(clockPosition());
        // 性能关键：lyricsUpdater 每秒触发一次，但歌词行通常 3~5s 才换一次。
        // 行号没变且不是换歌，跳过两次 IPC（setMetadata + setPlaybackState）。
        // AA 自己会基于 (position, lastUpdated, speed) 推算进度条，不需要每秒推送。
        if (idx == lastLyricsIdx && !lyricsChanged) return;
        lastLyricsIdx = idx;

        String current = "", next = "";
        if (idx >= 0) current = parsedLyrics.get(idx).second;
        if (idx + 1 < parsedLyrics.size()) next = parsedLyrics.get(idx + 1).second;

        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, current)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, next);
        Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if (art != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        if (durationMs > 0) b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        mSession.setMetadata(b.build());

        int code = (baseState == PlaybackStateCompat.STATE_NONE || baseState == PlaybackStateCompat.STATE_STOPPED)
                ? PlaybackStateCompat.STATE_PAUSED : baseState;
        PlaybackStateCompat.Builder ps = new PlaybackStateCompat.Builder()
                .setState(code, clockPosition(), (baseSpeed == 0f ? 1.0f : baseSpeed))
                .setActions(STANDARD_ACTIONS);
        addQqCustomActions(ps, lastPlayMode);
        mSession.setPlaybackState(ps.build());
    }

    private String readChosenPkg() {
        return getSharedPreferences(SP_SESSION, MODE_PRIVATE).getString(SP_LAST_PKG, null);
    }

    /** 持久化最新一首歌：下次冷启动可立即用作占位 metadata，减少"无内容"白屏。 */
    private void persistLastMeta(String title, String artist) {
        if (title == null) return;
        getSharedPreferences(SP_LAST_META, MODE_PRIVATE).edit()
                .putString("title", title)
                .putString("artist", artist != null ? artist : "")
                .apply();
    }

    /** 封面图取色：非 QQ 时按 ALBUM_ART → DISPLAY_ICON → ART 兜底；QQ 仅取 ALBUM_ART。 */
    private Bitmap pickAlbumArt(MediaMetadataCompat meta) {
        Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if (art != null || !isNcmMode) return art;
        art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
        return art;
    }

    /** 根据来源包是否 QQ 切换模式；进入非 QQ 时强制关闭歌词模式。 */
    private void switchModeForSource(String sourcePkg) {
        boolean toNonQqMode = !PKG_QQMUSIC.equals(sourcePkg);
        if (toNonQqMode == isNcmMode) return;
        isNcmMode = toNonQqMode;
        if (isNcmMode) {
            Log.i(TAG, "🔄 切换为【非 QQ 模式】，来源=" + sourcePkg);
            if (isLyricsMode) {
                exitLyricsMode();
                Log.i(TAG, "🧹 已关闭歌词模式（进入非 QQ）");
            }
        } else {
            Log.i(TAG, "🔄 切换为【QQ 模式】");
        }
    }

    // =========================================================
    // 📡 接收 QQ / NCM 的 Token 并构建 Controller（切源）
    // =========================================================
    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_CONTROLLER.equals(i.getAction())) return;

            String sourcePkg = i.getStringExtra("pkg");
            if (sourcePkg == null) {
                Log.w(TAG, "⚠️ 收到控制广播但缺少 pkg");
                return;
            }
            String chosenPkg = readChosenPkg();
            if (chosenPkg == null || !chosenPkg.equals(sourcePkg)) {
                Log.i(TAG, "ℹ️ 忽略来源 " + sourcePkg + "（当前选中=" + chosenPkg + "）");
                return;
            }

            switchModeForSource(sourcePkg);
            updateSessionActive("sourceChanged:" + sourcePkg);

            MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
            if (tk == null) {
                Log.w(TAG, "⚠️ 广播中没有 binder Token");
                return;
            }

            try {
                if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
                remoteCtrl = new MediaControllerCompat(MyMusicService.this, tk);
                remoteCtrl.registerCallback(remoteCb);
                Log.i(TAG, "✅ 已绑定远端控制器，pkg=" + sourcePkg);
                // 拿到 token 后立即停掉所有还在排队的发现/重试任务，省一波 IPC + 日志。
                stopDiscovery("got-token");
                disconnectAutoStartBrowser();
                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                updateSessionActive("tokenBound");
            } catch (Exception e) {
                Log.e(TAG, "❌ 绑定控制器失败", e);
            }
        }
    };


    private void parseLyrics(String rawLyrics) {
        parsedLyrics.clear();
        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2}\\.\\d{2})\\](.*)");
        for (String line : rawLyrics.split("\n")) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int min = Integer.parseInt(matcher.group(1));
                float sec = Float.parseFloat(matcher.group(2));
                long timeMs = (long) ((min * 60 + sec) * 1000);
                String text = matcher.group(3).trim();
                parsedLyrics.add(new Pair<>(timeMs, text));
            }
        }
    }

    // =========================================================
    // 🚀 启动服务：初始化本地 MediaSession 并设置转发逻辑
    // =========================================================
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_KEEP_ALIVE_START.equals(action)) {
            keepAliveEnabled = true;
            getSharedPreferences(SP_SETTINGS, MODE_PRIVATE).edit()
                    .putBoolean(SETTING_KEEP_ALIVE, true).apply();
            enterForegroundIfNeeded();
            Log.i(TAG, "🟢 keep-alive 启用");
            return START_STICKY;
        }
        if (ACTION_KEEP_ALIVE_STOP.equals(action)) {
            keepAliveEnabled = false;
            getSharedPreferences(SP_SETTINGS, MODE_PRIVATE).edit()
                    .putBoolean(SETTING_KEEP_ALIVE, false).apply();
            Log.i(TAG, "⚪ keep-alive 关闭");
            if (boundClientCount == 0) {
                exitForeground();
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        // 系统因 START_STICKY 重启时 intent==null：从 pref 恢复 keep-alive 态
        if (intent == null && keepAliveEnabled) {
            enterForegroundIfNeeded();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 读 keep-alive 偏好，决定是否需要在 onCreate 末尾自动进入前台
        keepAliveEnabled = getSharedPreferences(SP_SETTINGS, MODE_PRIVATE)
                .getBoolean(SETTING_KEEP_ALIVE, false);

        mSession = new MediaSessionCompat(this, "MirrorSession");
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS);
        setSessionToken(mSession.getSessionToken());

        seedInitialSessionContent();
        updateSessionActive("onCreate");

        mSession.setCallback(new MediaSessionCompat.Callback() {

            @Override public void onPlay() {
                if (remoteCtrl == null) return;
                optimisticPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                remoteCtrl.getTransportControls().play();
            }

            @Override public void onPause() {
                if (remoteCtrl == null) return;
                optimisticPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                remoteCtrl.getTransportControls().pause();
            }

            @Override public void onSkipToNext() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToNext();
            }

            @Override public void onSkipToPrevious() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToPrevious();
            }

            @Override public void onSeekTo(long positionMs) {
                if (remoteCtrl != null) remoteCtrl.getTransportControls().seekTo(positionMs);

                if (!isNcmMode && isLyricsMode) {
                    suppressRemoteState = true;
                    handler.removeCallbacks(clearSuppression);
                    handler.postDelayed(clearSuppression, 1200);

                    PlaybackStateCompat rs = lastRemoteState;
                    baseState = (rs != null) ? rs.getState() : PlaybackStateCompat.STATE_PLAYING;
                    baseSpeed = (rs != null) ? rs.getPlaybackSpeed() : 1.0f;
                    basePosMs = positionMs;
                    baseUpdateElapsed = SystemClock.elapsedRealtime();

                    lastLyricsIdx = -1; // seek 后强制下一次 overlay 真正写入
                    applyLyricsOverlay(lastRemoteMeta);
                } else {
                    PlaybackStateCompat remoteState =
                            (remoteCtrl != null) ? remoteCtrl.getPlaybackState() : null;
                    if (remoteState != null) mSession.setPlaybackState(remoteState);
                    updateSessionActive("seekTo");
                }
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (isNcmMode) return; // NCM 模式下忽略自定义按钮

                if (CUSTOM_ACTION_SHOW_LYRICS.equals(action)) {
                    if (isLyricsMode) {
                        exitLyricsMode();
                    } else {
                        enterLyricsMode();
                    }
                    if (remoteCtrl != null) {
                        mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                    }
                    updateSessionActive("toggleLyrics=" + isLyricsMode);

                } else if (CUSTOM_ACTION_REPEAT_MODE.equals(action)) {
                    Intent intent = new Intent(QQ_ACTION_PLAY_MODE_WIDGET);
                    intent.setPackage(PKG_QQMUSIC);
                    sendBroadcast(intent);
                    handler.postDelayed(() -> {
                        if (remoteCtrl != null) {
                            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                        }
                    }, 500);
                }
            }
        });

        // 同时注册 QQ / NCM 的 Token 广播
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(tokenRx, new IntentFilter(ACTION_CONTROLLER));

        // 主动唤醒 Sniffer：NotificationListenerService 可能长期处于未绑定状态，
        // 导致 onGetRoot 发出的 REQUEST_TOKEN 没有接收方。requestRebind 对"从未连接"
        // 和"曾经断开"均有效，前提是用户已授予通知使用权。
        requestSnifferRebind();



        // 注册"自动歌词模式"广播
        lbm.registerReceiver(autoLyricsReceiver, new IntentFilter(ACTION_TOGGLE_LYRICS_MODE));

        // 自动歌词模式：仅在 QQ 模式下可自动开启（NCM 模式忽略）
        boolean autoLyrics = getSharedPreferences(SP_SETTINGS, MODE_PRIVATE)
                .getBoolean("autoLyrics", false);
        Log.i(TAG, "🎚 autoLyrics = " + autoLyrics);
        if (autoLyrics && !isNcmMode && !isLyricsMode) {
            enterLyricsMode();
        }

        // keep-alive 模式：进程刚起来就提到前台，不等 AA bind
        if (keepAliveEnabled) enterForegroundIfNeeded();
    }

    // =========================================================
    // 🧹 资源释放
    // =========================================================
    @Override
    public void onDestroy() {
        exitForeground();
        // 清掉所有 handler 上的 pending 任务，避免 service 销毁后 lambda 持有 this 引用。
        handler.removeCallbacksAndMessages(null);
        if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
        disconnectAutoStartBrowser();
        unregisterProbeObserver();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(tokenRx);
        lbm.unregisterReceiver(autoLyricsReceiver);
        mSession.release();
        super.onDestroy();
    }

    // =========================================================
    // 🚪 MediaBrowser 接口（供 Android Auto 探测）
    // =========================================================
    // =========================================================
    // 🔌 AA bind 生命周期 → 前台 Service 开关
    // =========================================================
    @Override
    public IBinder onBind(Intent intent) {
        IBinder binder = super.onBind(intent);
        boundClientCount++;
        enterForegroundIfNeeded();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boolean res = super.onUnbind(intent);
        boundClientCount = Math.max(0, boundClientCount - 1);
        // keep-alive 开启时不退出前台；只有"无 client 绑定 + 用户未要求保活"才退出
        if (boundClientCount == 0 && !keepAliveEnabled) exitForeground();
        return res;
    }

    private void enterForegroundIfNeeded() {
        if (inForeground) return;
        try {
            ensureNotificationChannel();
            Notification n = buildForegroundNotification();
            // Android 10+ 推荐 3-arg 形式；14+ 要求 service 声明 foregroundServiceType 时必须显式传。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(FG_NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FG_NOTIFICATION_ID, n);
            }
            inForeground = true;
            Log.i(TAG, "▶ enterForeground (boundClients=" + boundClientCount + ")");
        } catch (Throwable t) {
            // 启动前台失败不应让 service 崩溃；继续按普通 bound service 运行
            Log.w(TAG, "enterForeground 失败: " + t.getMessage());
        }
    }

    private void exitForeground() {
        if (!inForeground) return;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
            inForeground = false;
            Log.i(TAG, "⏹ exitForeground");
        } catch (Throwable t) {
            Log.w(TAG, "exitForeground 失败: " + t.getMessage());
        }
    }

    private void ensureNotificationChannel() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(FG_CHANNEL_ID) != null) return;
        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                FG_CHANNEL_ID, "车机模式", android.app.NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Android Auto 连接期间保持运行");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, FG_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("糯米播放器")
                .setContentText("车机模式运行中")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(new MediaStyle().setMediaSession(mSession.getSessionToken()))
                .build();
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid,
                                 Bundle rootHints) {
        // AA 每次 bind / 重连都会调此方法 —— 重启 60s 发现循环。
        startDiscovery();
        // 拿不到 token 时再试着在后台 bind 目标 App 的 MediaBrowserService 触发 play()。
        if (remoteCtrl == null) autoStartLastApp();
        return new BrowserRoot("root", null);
    }

    private void autoStartLastApp() {
        autoStartAttempts = 0;
        autoStartLastAppOnce();
    }

    private void autoStartLastAppOnce() {
        String pkg = readChosenPkg();
        if (pkg == null) {
            Log.i(TAG, "autoStart: 未选中任何 App，跳过");
            return;
        }

        ComponentName cn = resolveMediaBrowserService(pkg);
        if (cn == null) return;
        Log.i(TAG, "autoStart: 连接 " + cn.flattenToShortString());

        disconnectAutoStartBrowser();
        autoStartBrowser = new MediaBrowserCompat(this, cn,
                new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() {
                        Log.i(TAG, "autoStart: 已连接，发送 play()");
                        MediaSessionCompat.Token token = autoStartBrowser.getSessionToken();
                        try {
                            new MediaControllerCompat(MyMusicService.this, token)
                                    .getTransportControls().play();
                        } catch (Exception e) {
                            Log.w(TAG, "autoStart: play() 失败", e);
                        }
                        // 路径 B 观察：注册一个临时 callback，看从 MediaBrowserService 直接拿的
                        // SessionToken 是否真能持续收到 metadata / playback state。
                        startPathBObservation(token, pkg);
                        // play() 触发后 Sniffer 会接管，留出观察窗口后再断开
                        handler.postDelayed(MyMusicService.this::disconnectAutoStartBrowser,
                                PATH_B_OBSERVE_DURATION_MS);
                    }
                    @Override public void onConnectionFailed() {
                        Log.w(TAG, "autoStart: 连接失败 pkg=" + pkg + " attempt=" + autoStartAttempts);
                        autoStartBrowser = null;
                        scheduleAutoStartRetry();
                    }
                    @Override public void onConnectionSuspended() {
                        Log.w(TAG, "autoStart: 连接挂起 pkg=" + pkg + " attempt=" + autoStartAttempts);
                        autoStartBrowser = null;
                        scheduleAutoStartRetry();
                    }
                }, null);

        autoStartBrowser.connect();
        autoStartAttempts++;
    }

    private ComponentName resolveMediaBrowserService(String pkg) {
        Intent query = new Intent(MEDIA_BROWSER_SERVICE_ACTION).setPackage(pkg);
        List<ResolveInfo> services;
        try {
            services = getPackageManager().queryIntentServices(query, 0);
        } catch (Exception e) {
            Log.w(TAG, "autoStart: 查询服务失败 pkg=" + pkg, e);
            return null;
        }
        if (services == null || services.isEmpty()) {
            Log.w(TAG, "autoStart: " + pkg + " 未暴露 MediaBrowserService，跳过");
            return null;
        }
        return new ComponentName(services.get(0).serviceInfo.packageName,
                services.get(0).serviceInfo.name);
    }

    private void disconnectAutoStartBrowser() {
        if (autoStartBrowser == null) return;
        try { autoStartBrowser.disconnect(); } catch (Throwable ignore) {}
        autoStartBrowser = null;
        unregisterProbeObserver();
    }

    // ===== 路径 B 观察 =====

    private void startPathBObservation(MediaSessionCompat.Token token, String pkg) {
        unregisterProbeObserver();
        probeSawMetadata = false;
        probeSawState = false;
        try {
            probeCtrl = new MediaControllerCompat(this, token);
            probeCb = new MediaControllerCompat.Callback() {
                @Override public void onMetadataChanged(MediaMetadataCompat m) {
                    probeSawMetadata = true;
                    String title = (m != null) ? m.getString(MediaMetadataCompat.METADATA_KEY_TITLE) : null;
                    Log.i(PATH_B_TAG, "✅ metadata via MediaBrowserService token pkg=" + pkg
                            + " title=" + title);
                }
                @Override public void onPlaybackStateChanged(PlaybackStateCompat s) {
                    probeSawState = true;
                    Log.i(PATH_B_TAG, "✅ state via MediaBrowserService token pkg=" + pkg
                            + " state=" + (s != null ? s.getState() : "null"));
                }
            };
            probeCtrl.registerCallback(probeCb);
            // 立即取一次当前快照，看 token 是否带初始内容
            MediaMetadataCompat initMeta = probeCtrl.getMetadata();
            PlaybackStateCompat initState = probeCtrl.getPlaybackState();
            Log.i(PATH_B_TAG, "🔬 注册观察 pkg=" + pkg
                    + " initMeta=" + (initMeta != null)
                    + " initState=" + (initState != null ? initState.getState() : "null"));
            // 观察窗口结束后汇总结论
            handler.postDelayed(this::summarizePathB, PATH_B_OBSERVE_DURATION_MS);
        } catch (Throwable t) {
            Log.w(PATH_B_TAG, "观察初始化失败: " + t.getMessage());
        }
    }

    private void summarizePathB() {
        Log.i(PATH_B_TAG, "📊 观察窗口结束: sawMetadata=" + probeSawMetadata
                + " sawState=" + probeSawState);
    }

    private void unregisterProbeObserver() {
        if (probeCtrl != null && probeCb != null) {
            try { probeCtrl.unregisterCallback(probeCb); } catch (Throwable ignore) {}
        }
        probeCtrl = null;
        probeCb = null;
    }

    private void scheduleAutoStartRetry() {
        if (remoteCtrl != null) return;
        if (autoStartAttempts >= AUTO_START_MAX_ATTEMPTS) {
            Log.i(TAG, "autoStart: 已达最大重试次数，放弃");
            return;
        }
        handler.postDelayed(() -> {
            if (remoteCtrl == null) autoStartLastAppOnce();
        }, AUTO_START_RETRY_DELAY_MS);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        // AA 每次重新查浏览树时也是个"我还在等内容"信号 —— 重启发现循环窗口。
        if (remoteCtrl == null) startDiscovery();
        result.sendResult(Collections.emptyList());
    }
}
