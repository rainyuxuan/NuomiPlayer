package com.nuomi;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

public class MusicSessionSniffer extends NotificationListenerService {

    private static final String TAG = "Sniffer";
    private static final String ACTION_CONTROLLER = "com.nuomi.ACTION_CONTROLLER";
    private static final String ACTION_REQ_TOKEN  = "com.nuomi.REQUEST_TOKEN";
    private static final String SP_SESSION = "session_pref";
    private static final String SP_LAST_PKG = "last_pkg";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_BINDER = "binder";
    // Sniffer 刚连上时 QQ MediaSession 可能还没注册，安排几次延迟重查兜底。
    private static final long[] POST_CONNECT_REFRESH_DELAYS_MS = {1500L, 4000L, 9000L};

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private MediaController selectedCtrl;            // 当前选中包名对应的 controller
    private MediaController.Callback selectedCb;     // 当前 controller 上的回调（切换时 unregister 防泄漏）
    private String selectedPkg;                      // 当前选中的包名（从 SP 读取）

    // onListenerConnected 是否触发过 —— 没触发时 getActiveSessions() 会抛 SecurityException。
    private boolean isListenerConnected = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsCb =
            sessions -> {
                Log.i(TAG, "🔄 活跃会话变化 → 数量=" + (sessions == null ? 0 : sessions.size()));
                refreshAndBroadcast();
            };

    private final BroadcastReceiver reqTokenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i(TAG, "📨 收到 REQUEST_TOKEN (connected=" + isListenerConnected + ")");
            if (!isListenerConnected) {
                // 等 onListenerConnected() 后会自动 refresh，这里不需要积压补发。
                Log.w(TAG, "⏳ Sniffer 未就绪，忽略");
                return;
            }
            refreshAndBroadcast();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Log.i(TAG, "🚀 MusicSessionSniffer 启动");
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(reqTokenRx, new IntentFilter(ACTION_REQ_TOKEN));
    }

    @Override public void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        clearSelectedCallback();
        removeActiveSessionsListener();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reqTokenRx);
        Log.i(TAG, "🛑 MusicSessionSniffer 已销毁");
        super.onDestroy();
    }

    @Override public void onListenerConnected() {
        isListenerConnected = true;
        Log.i(TAG, "🔌 已连接到通知监听服务");
        MediaSessionManager sm = mediaSessionManager();
        if (sm != null) {
            try {
                sm.addOnActiveSessionsChangedListener(activeSessionsCb, selfComponent());
            } catch (SecurityException e) {
                Log.w(TAG, "⚠️ 注册活跃会话监听失败: " + e.getMessage());
            }
        }
        refreshAndBroadcast();
        scheduleDelayedRefresh();
    }

    @Override public void onListenerDisconnected() {
        isListenerConnected = false;
        removeActiveSessionsListener();
        clearSelectedCallback();
        // 主动请求系统尽快重绑，减少三星等激进省电机制下的重连延迟。
        try { requestRebind(selfComponent()); } catch (Throwable ignore) {}
        Log.i(TAG, "⚡ 已请求重绑通知监听服务");
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String want = readChosenPkg();
        if (want != null && want.equals(sbn.getPackageName())) {
            Log.i(TAG, "🔔 收到目标 App 通知: " + want);
            refreshAndBroadcast();
        }
    }

    // =========================================================
    // 核心：找到目标 session 并把 token 广播出去
    // =========================================================
    private void refreshAndBroadcast() {
        refreshSelectedController();
        sendTokenIfAny();
    }

    private void refreshSelectedController() {
        selectedPkg = readChosenPkg();
        if (selectedPkg == null) {
            clearSelectedCallback();
            selectedCtrl = null;
            Log.w(TAG, "⚠️ 未选中任何 App");
            return;
        }
        MediaController found = findControllerForPkg(selectedPkg);
        if (found == null) {
            Log.w(TAG, "⚠️ 选中的 App 没有活跃会话: " + selectedPkg);
            clearSelectedCallback();
            selectedCtrl = null;
            return;
        }
        bindController(found);
    }

    @Nullable
    private MediaController findControllerForPkg(String pkg) {
        MediaSessionManager sm = mediaSessionManager();
        if (sm == null) {
            Log.w(TAG, "❌ 无法获取 MediaSessionManager");
            return null;
        }
        List<MediaController> list = null;
        try { list = sm.getActiveSessions(selfComponent()); } catch (SecurityException ignore) {}
        if (list == null || list.isEmpty()) {
            try { list = sm.getActiveSessions(null); } catch (Throwable ignore) {}
        }
        Log.i(TAG, "📊 活跃会话数量 = " + (list == null ? 0 : list.size()));
        if (list == null) return null;
        for (MediaController c : list) {
            if (pkg.equals(c.getPackageName())) return c;
        }
        return null;
    }

    private void bindController(MediaController found) {
        if (selectedCtrl != null
                && selectedCb != null
                && selectedCtrl.getSessionToken().equals(found.getSessionToken())) {
            return; // 已绑定同一个 session
        }
        // 切换 controller 前先 unregister 旧的回调，避免每次 session 变化泄漏一个 Callback。
        clearSelectedCallback();
        selectedCtrl = found;
        Log.i(TAG, "🎶 绑定到新会话: " + selectedPkg);
        dumpCapabilities(selectedCtrl);

        selectedCb = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata meta)  { dumpMeta(selectedPkg, meta); }
            @Override public void onPlaybackStateChanged(PlaybackState s){ dumpState(selectedPkg, s);   }
        };
        selectedCtrl.registerCallback(selectedCb);
        dumpMeta(selectedPkg, selectedCtrl.getMetadata());
        dumpState(selectedPkg, selectedCtrl.getPlaybackState());
    }

    private void sendTokenIfAny() {
        if (selectedCtrl == null || selectedPkg == null) return;
        MediaSessionCompat.Token compat = MediaSessionCompat.Token.fromToken(selectedCtrl.getSessionToken());
        Intent i = new Intent(ACTION_CONTROLLER)
                .putExtra(EXTRA_PKG, selectedPkg)
                .putExtra(EXTRA_BINDER, compat);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        Log.i(TAG, "📡 已发送 Token: " + selectedPkg);
    }

    private void scheduleDelayedRefresh() {
        refreshHandler.removeCallbacksAndMessages(null);
        for (long d : POST_CONNECT_REFRESH_DELAYS_MS) {
            refreshHandler.postDelayed(() -> {
                if (selectedCtrl == null) {
                    Log.i(TAG, "🔍 延迟重查活跃会话 (delay=" + d + "ms)");
                    refreshAndBroadcast();
                }
            }, d);
        }
    }

    // =========================================================
    // 工具
    // =========================================================
    private void clearSelectedCallback() {
        if (selectedCtrl != null && selectedCb != null) {
            try { selectedCtrl.unregisterCallback(selectedCb); } catch (Throwable ignore) {}
        }
        selectedCb = null;
    }

    private void removeActiveSessionsListener() {
        MediaSessionManager sm = mediaSessionManager();
        if (sm == null) return;
        try { sm.removeOnActiveSessionsChangedListener(activeSessionsCb); } catch (Throwable ignore) {}
    }

    private MediaSessionManager mediaSessionManager() {
        return (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
    }

    private ComponentName selfComponent() {
        return new ComponentName(this, MusicSessionSniffer.class);
    }

    @Nullable
    private String readChosenPkg() {
        SharedPreferences sp = getSharedPreferences(SP_SESSION, MODE_PRIVATE);
        return sp.getString(SP_LAST_PKG, null);
    }

    /* --- 调试打印 --- */
    private void dumpMeta(String pkg, MediaMetadata meta) {
        if (meta == null) return;
        Log.i(TAG, pkg + " 元数据 → 歌曲: " + meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                + " | 歌手: " + meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                + " | 时长: " + meta.getLong(MediaMetadata.METADATA_KEY_DURATION) + "ms");
    }
    private void dumpState(String pkg, PlaybackState st) {
        if (st == null) return;
        String s = st.getState() == PlaybackState.STATE_PLAYING ? "播放中"
                : st.getState() == PlaybackState.STATE_PAUSED ? "已暂停" : String.valueOf(st.getState());
        Log.i(TAG, pkg + " 播放状态 → " + s + " | 位置=" + st.getPosition() + "ms");
    }
    private void dumpCapabilities(MediaController ctrl) {
        PlaybackState st = ctrl.getPlaybackState();
        long a = st != null ? st.getActions() : 0;
        Log.i(TAG, "可用操作=" + a + " | 包名=" + ctrl.getPackageName());
    }
}
