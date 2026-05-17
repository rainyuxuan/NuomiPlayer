package com.nuomi;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.SharedPreferences;


import androidx.annotation.Nullable;


import android.content.Context;



public class MusicSessionSniffer extends NotificationListenerService {

    private static final String ACTION_CONTROLLER = "com.nuomi.ACTION_CONTROLLER";
    private static final String ACTION_REQ_TOKEN  = "com.nuomi.REQUEST_TOKEN";

    private MediaController selectedCtrl;   // 当前选中包名对应的 controller
    private MediaController.Callback selectedCb; // 当前 controller 上的回调（用于切换时 unregister，避免泄漏）
    private String selectedPkg;             // 当前选中的包名（从 SP 读取）

    // 监听系统活跃会话变化：当 QQ 启停或 token 更换、且没有伴随通知时，仍然可以感知。
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsCb =
            sessions -> {
                Log.i("Sniffer", "🔄 活跃会话变化 → 数量=" + (sessions == null ? 0 : sessions.size()));
                refreshSelectedController();
                sendTokenIfAny();
            };

    // 标记 onListenerConnected() 是否已触发，避免在未连接时误判 getActiveSessions() 结果。
    private boolean isListenerConnected = false;
    // 在 Sniffer 连接前收到的 REQUEST_TOKEN，连接后补发。
    private boolean pendingTokenRequest = false;

    @Override public void onCreate() {
        super.onCreate();
        Log.i("Sniffer", "🚀 MusicSessionSniffer 启动");
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(reqTokenRx, new IntentFilter(ACTION_REQ_TOKEN));
    }

    @Override public void onDestroy() {
        clearSelectedCallback();
        MediaSessionManager sm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sm != null) {
            try { sm.removeOnActiveSessionsChangedListener(activeSessionsCb); } catch (Throwable ignore) {}
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reqTokenRx);
        Log.i("Sniffer", "🛑 MusicSessionSniffer 已销毁");
        super.onDestroy();
    }

    @Override public void onListenerConnected() {
        isListenerConnected = true;
        Log.i("Sniffer", "🔌 已连接到通知监听服务");
        MediaSessionManager sm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sm != null) {
            try {
                ComponentName me = new ComponentName(this, MusicSessionSniffer.class);
                sm.addOnActiveSessionsChangedListener(activeSessionsCb, me);
            } catch (SecurityException e) {
                Log.w("Sniffer", "⚠️ 注册活跃会话监听失败: " + e.getMessage());
            }
        }
        // pendingTokenRequest 只是说明连接前有过请求；无论如何都要刷新一次，无需双调用。
        if (pendingTokenRequest) {
            Log.i("Sniffer", "🔁 补发连接前积压的 Token 请求");
            pendingTokenRequest = false;
        }
        refreshSelectedController();
        sendTokenIfAny();
    }

    @Override public void onListenerDisconnected() {
        isListenerConnected = false;
        MediaSessionManager sm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sm != null) {
            try { sm.removeOnActiveSessionsChangedListener(activeSessionsCb); } catch (Throwable ignore) {}
        }
        clearSelectedCallback();
        // 主动请求系统尽快重绑，减少三星等激进省电机制下的重连延迟。
        try {
            requestRebind(new ComponentName(this, MusicSessionSniffer.class));
        } catch (Throwable ignore) {}
        Log.i("Sniffer", "⚡ 已请求重绑通知监听服务");
        super.onListenerDisconnected();
    }

    private void clearSelectedCallback() {
        if (selectedCtrl != null && selectedCb != null) {
            try { selectedCtrl.unregisterCallback(selectedCb); } catch (Throwable ignore) {}
        }
        selectedCb = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        String want = readChosenPkg();
        if (want == null) return;
        if (want.equals(pkg)) {
            Log.i("Sniffer", "🔔 收到来自已选中应用的通知: " + pkg);
            refreshSelectedController();
            sendTokenIfAny();
        }
    }

    private final BroadcastReceiver reqTokenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i("Sniffer", "📨 收到立即重发 Token 请求 (isListenerConnected=" + isListenerConnected + ")");
            if (!isListenerConnected) {
                // Sniffer 尚未连接到系统，getActiveSessions() 会抛 SecurityException。
                // 记录下来，等 onListenerConnected() 后补发。
                pendingTokenRequest = true;
                Log.w("Sniffer", "⏳ Sniffer 未就绪，Token 请求已排队");
                return;
            }
            refreshSelectedController();
            sendTokenIfAny();
        }
    };

    @Nullable
    private String readChosenPkg() {
        SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
        return sp.getString("last_pkg", null);
    }

    private void refreshSelectedController() {
        selectedPkg = readChosenPkg();
        if (selectedPkg == null) {
            clearSelectedCallback();
            selectedCtrl = null;
            Log.w("Sniffer", "⚠️ 当前没有用户选中的应用");
            return;
        }

        MediaSessionManager sm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sm == null) { Log.w("Sniffer", "❌ 无法获取 MediaSessionManager"); return; }

        ComponentName me = new ComponentName(this, MusicSessionSniffer.class);
        java.util.List<MediaController> list = null;
        try { list = sm.getActiveSessions(me); } catch (SecurityException ignore) {}
        if (list == null || list.isEmpty()) {
            try { list = sm.getActiveSessions(null); } catch (Throwable ignore) {}
        }
        Log.i("Sniffer", "📊 活跃会话数量 = " + (list == null ? 0 : list.size()));
        if (list == null) { selectedCtrl = null; return; }

        MediaController found = null;
        for (MediaController c : list) {
            if (selectedPkg.equals(c.getPackageName())) {
                found = c;
                break;
            }
        }

        if (found == null) {
            Log.w("Sniffer", "⚠️ 当前选中的应用没有活跃会话: " + selectedPkg);
            clearSelectedCallback();
            selectedCtrl = null;
            return;
        }

        if (selectedCtrl == null ||
                selectedCb == null ||
                !selectedCtrl.getSessionToken().equals(found.getSessionToken())) {
            // 切换 controller 前先 unregister 旧的回调，避免每次 session 变化都泄漏一个 Callback。
            clearSelectedCallback();

            selectedCtrl = found;
            Log.i("Sniffer", "🎶 绑定到新会话: " + selectedPkg);
            dumpCapabilities(selectedCtrl);

            selectedCb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata meta)  { dumpMeta(selectedPkg, meta); }
                @Override public void onPlaybackStateChanged(PlaybackState s){ dumpState(selectedPkg, s);   }
            };
            selectedCtrl.registerCallback(selectedCb);

            dumpMeta(selectedPkg, selectedCtrl.getMetadata());
            dumpState(selectedPkg, selectedCtrl.getPlaybackState());
        }
    }

    private void sendTokenIfAny() {
        if (selectedCtrl == null || selectedPkg == null) return;
        MediaSessionCompat.Token compat = MediaSessionCompat.Token.fromToken(selectedCtrl.getSessionToken());
        Intent i = new Intent(ACTION_CONTROLLER);
        i.putExtra("pkg", selectedPkg);
        i.putExtra("binder", compat);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        Log.i("Sniffer", "📡 已发送 Token 给应用: " + selectedPkg);
    }

    /* --- 调试打印（中文版本） --- */
    private void dumpMeta(String pkg, MediaMetadata meta) {
        if (meta == null) return;
        Log.i("Sniffer", pkg + " 元数据 → 歌曲: " + meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                + " | 歌手: " + meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                + " | 时长: " + meta.getLong(MediaMetadata.METADATA_KEY_DURATION) + "ms");
    }
    private void dumpState(String pkg, PlaybackState st) {
        if (st == null) return;
        String s = st.getState() == PlaybackState.STATE_PLAYING ? "播放中"
                : st.getState() == PlaybackState.STATE_PAUSED ? "已暂停" : String.valueOf(st.getState());
        Log.i("Sniffer", pkg + " 播放状态 → " + s + " | 位置=" + st.getPosition() + "ms");
    }
    private void dumpCapabilities(MediaController ctrl) {
        PlaybackState st = ctrl.getPlaybackState();
        long a = st != null ? st.getActions() : 0;
        Log.i("Sniffer", "可用操作=" + a + " | 包名=" + ctrl.getPackageName());
    }
}
