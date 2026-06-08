package com.nuomi;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.nuomi.shared.MyMusicService;

/**
 * 开机 / 应用更新后主动请求重绑 Sniffer，让 NLS 在用户插车前就处于已连接状态。
 *
 * 系统对 NotificationListenerService 的初次绑定时机是策略决定的，可能延迟数秒到数十秒。
 * 把这个延迟尽可能前移到 boot 时段，AA 接入时就少一段等待窗口。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        try {
            ComponentName cn = new ComponentName(context, MusicSessionSniffer.class);
            NotificationListenerService.requestRebind(cn);
            Log.i(TAG, "🛎 已请求重绑 Sniffer (trigger=" + action + ")");
        } catch (Throwable t) {
            Log.w(TAG, "requestRebind 失败: " + t.getMessage());
        }

        // 如果用户开了后台保活，开机时直接把 MyMusicService 提到前台。
        // 这样不必等 AA 连接也能让 NLS / 进程一直保持活跃状态。
        SharedPreferences sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (sp.getBoolean(MyMusicService.SETTING_KEEP_ALIVE, false)) {
            try {
                Intent svc = new Intent(context, MyMusicService.class)
                        .setAction(MyMusicService.ACTION_KEEP_ALIVE_START);
                ContextCompat.startForegroundService(context, svc);
                Log.i(TAG, "🟢 keep-alive 已请求启动 MyMusicService");
            } catch (Throwable t) {
                Log.w(TAG, "启动 keep-alive 失败: " + t.getMessage());
            }
        }
    }
}
