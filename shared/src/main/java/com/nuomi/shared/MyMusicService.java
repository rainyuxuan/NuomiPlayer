package com.nuomi.shared;

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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;

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

    private int lastPlayMode = 0; // QQ 的播放模式缓存

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

    private static final String TAG = "Mirror";

    // mobile 模块里的 Sniffer 组件名（shared 不能直接引用 mobile 类，硬编码字符串避免循环依赖）
    private static final String SNIFFER_CLASS = "com.nuomi.MusicSessionSniffer";
    private static final String MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService";
    private static final int AUTO_START_MAX_ATTEMPTS = 3;
    private static final long AUTO_START_RETRY_DELAY_MS = 2000L;

    // ===== 发现循环（AA bind 后用来一直找 token） =====
    // 关键路径：用户的理想流程是"QQ 已播放 → AA 启动 → Bixby 拉起糯米"。
    // Bixby 拉起糯米可能比 AA bind 慢几十秒；Sniffer NLS 绑上也可能慢。
    // 用一个 60s 的自适应循环，每次 tick 之间逐步退避（0.5s→8s 上限），
    // 拿到 remoteCtrl 立刻停。每次 onGetRoot/onLoadChildren 都会重启循环计时。
    private long discoveryStartElapsed = 0L;
    private long discoveryNextDelay = 0L;
    private boolean discoveryRunning = false;
    private static final long DISCOVERY_WINDOW_MS = 60_000L;
    private static final long DISCOVERY_INITIAL_DELAY_MS = 500L;
    private static final long DISCOVERY_MAX_DELAY_MS = 8_000L;

    private final Runnable discoveryTick = new Runnable() {
        @Override public void run() {
            if (remoteCtrl != null) {
                discoveryRunning = false;
                Log.i(TAG, "✅ 发现循环已拿到 token，停止");
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - discoveryStartElapsed;
            if (elapsed > DISCOVERY_WINDOW_MS) {
                discoveryRunning = false;
                Log.i(TAG, "⏰ 发现循环超时（60s）放弃");
                return;
            }
            Log.i(TAG, "🔁 发现循环 tick elapsed=" + elapsed + "ms");
            LocalBroadcastManager.getInstance(MyMusicService.this)
                    .sendBroadcast(new Intent(ACTION_REQUEST_TOKEN));
            requestSnifferRebind();

            // 自适应退避：0.5 → 1 → 2 → 4 → 8s 后保持
            discoveryNextDelay = Math.min(DISCOVERY_MAX_DELAY_MS,
                    Math.max(DISCOVERY_INITIAL_DELAY_MS, discoveryNextDelay * 2));
            handler.postDelayed(this, discoveryNextDelay);
        }
    };

    private void startDiscovery() {
        // 重置窗口起点：每次 AA 来 bind 都重新给 60s 窗口。
        discoveryStartElapsed = SystemClock.elapsedRealtime();
        discoveryNextDelay = DISCOVERY_INITIAL_DELAY_MS;
        if (discoveryRunning) {
            Log.i(TAG, "🔄 发现循环已运行，重置窗口");
            return;
        }
        discoveryRunning = true;
        Log.i(TAG, "🚦 启动发现循环（60s 窗口）");
        // 立即触发一次
        handler.post(discoveryTick);
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
        snapshotBaseFromRemote();
        handler.post(lyricsUpdater);
        if (remoteCtrl != null) {
            applyLyricsOverlay(lastRemoteMeta);
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
        }
    }

    /** 关闭歌词模式：停定时器、清拖动保护期。 */
    private void exitLyricsMode() {
        isLyricsMode = false;
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
        if (meta != null) mirrorMetadata(meta);
        if (st != null) mirrorPlaybackState(meta, st);
    }

    private void mirrorMetadata(MediaMetadataCompat meta) {
        if (!isNcmMode && isLyricsMode) {
            applyLyricsOverlay(meta); // QQ 歌词模式：覆盖为"当前句/下一句"
            return;
        }
        // QQ 非歌词模式 或 NCM 模式：原样映射标准字段
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

    private void mirrorPlaybackState(MediaMetadataCompat meta, PlaybackStateCompat st) {
        // QQ 歌词模式分支（NCM 强制关闭歌词，不走此分支）
        if (!isNcmMode && isLyricsMode) {
            if (!suppressRemoteState) {
                basePosMs = st.getPosition();
                baseSpeed = st.getPlaybackSpeed();
                baseState = st.getState();
                baseUpdateElapsed = SystemClock.elapsedRealtime();
            }
            int code = st.getState();
            // STATE_NONE/STOPPED 时强制 PAUSED，避免 AA 跳回浏览页
            if (code == PlaybackStateCompat.STATE_NONE || code == PlaybackStateCompat.STATE_STOPPED) {
                code = PlaybackStateCompat.STATE_PAUSED;
            }
            PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setState(code, clockPosition(), (baseSpeed == 0f ? 1.0f : baseSpeed))
                    .setActions(STANDARD_ACTIONS);
            addQqCustomActions(builder, lastPlayMode);
            mSession.setPlaybackState(builder.build());
            return;
        }

        // QQ 非歌词 / NCM 通用分支：跳过 NONE/STOPPED
        int code = st.getState();
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
        if (lyricsWhole != null && !lyricsWhole.equals(lastLyricsRaw)) {
            lastLyricsRaw = lyricsWhole;
            parseLyrics(lyricsWhole);
        }

        String current = "", next = "";
        if (!parsedLyrics.isEmpty()) {
            int idx = findLyricsIndex(clockPosition());
            if (idx >= 0) current = parsedLyrics.get(idx).second;
            if (idx + 1 < parsedLyrics.size()) next = parsedLyrics.get(idx + 1).second;
        }

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
    public void onCreate() {
        super.onCreate();

        mSession = new MediaSessionCompat(this, "MirrorSession");
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS);
        setSessionToken(mSession.getSessionToken());

        seedInitialSessionContent();
        updateSessionActive("onCreate");

        mSession.setCallback(new MediaSessionCompat.Callback() {

            @Override public void onPlay() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().play();
            }

            @Override public void onPause() {
                if (remoteCtrl != null)
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

        // 主动唤醒 Sniffer：三星等省电策略下 NotificationListenerService 可能长期处于
        // 未绑定状态，导致 onGetRoot 发出的 REQUEST_TOKEN 永远没人接。无论 Sniffer 当前
        // 是否还活着，都让系统重新绑定一次 —— requestRebind 对"从未连接"和"曾经断开"
        // 都成立，前提是用户授予过通知使用权。
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
    }

    // =========================================================
    // 🧹 资源释放
    // =========================================================
    @Override
    public void onDestroy() {
        // 清掉所有 handler 上的 pending 任务，避免 service 销毁后 lambda 持有 this 引用。
        handler.removeCallbacksAndMessages(null);
        if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
        disconnectAutoStartBrowser();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(tokenRx);
        lbm.unregisterReceiver(autoLyricsReceiver);
        mSession.release();
        super.onDestroy();
    }

    // =========================================================
    // 🚪 MediaBrowser 接口（供 Android Auto 探测）
    // =========================================================
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
                        try {
                            new MediaControllerCompat(MyMusicService.this,
                                    autoStartBrowser.getSessionToken())
                                    .getTransportControls().play();
                        } catch (Exception e) {
                            Log.w(TAG, "autoStart: play() 失败", e);
                        }
                        // play() 触发后 Sniffer 会接管，3 秒后断开自动连接
                        handler.postDelayed(MyMusicService.this::disconnectAutoStartBrowser, 3000);
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
