package com.tv.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 开机自启（通知监听服务被系统绑定）。
 *
 * 本机固件的第三方应用收不到 BOOT_COMPLETED，JobScheduler 的持久化任务重启后
 * 也没有被恢复，所以改用「系统主动绑定」这条通道：
 * NotificationManagerService 在开机后会重新绑定所有已授权的通知监听服务，
 * 绑定成功即回调 onListenerConnected()，此时把桌面拉起来。
 *
 * 授权方式（一次性，shell 可做）：
 *   cmd notification allow_listener com.tv.tvhome/.BootListener
 *
 * 触发规则（v1.5.1 起）：
 *   1. 每次开机后的第一次连接 → 一定拉起桌面（用 Settings.Global.BOOT_COUNT 判定）；
 *   2. 同一次开机内的后续重连 → 只在「距首次连接 3 分钟内」才补拉。
 *
 * 为什么不用「开机后 N 分钟内」的绝对 uptime 判断：实测该判断不可靠
 * （tvcleaner 侧出现过使用中 uptime 已 660s 仍被触发的情况）。改成
 * 「首次连接时刻 + 相对时间差」后，即使监听器在用户看视频时重连也不会抢前台。
 */
public class BootListener extends NotificationListenerService {

    private static final String TAG = "TVHome";
    private static final String PREFS = "tvhome_boot";
    private static final String KEY_BOOT = "last_boot_count";
    private static final String KEY_FIRST = "first_connect_elapsed";
    /** 同一次开机内允许补拉的相对时间窗口。 */
    private static final long RETRY_WINDOW_MS = 180000L;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (MainActivity.RESUMED) {
            Log.i(TAG, "listener: already on top, skip");
            return;
        }
        if (!shouldTakeFocus(this)) {
            Log.i(TAG, "listener: outside boot window, skip");
            return;
        }
        Log.i(TAG, "listener: bring home to front");
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "listener start home fail", t);
        }
    }

    private static boolean shouldTakeFocus(Context ctx) {
        int bootCount = -1;
        try {
            bootCount = Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) { }

        long now = SystemClock.elapsedRealtime();
        android.content.SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (bootCount < 0) {
            // 取不到 BOOT_COUNT：退回原来的绝对窗口判断
            return now < RETRY_WINDOW_MS;
        }
        int lastBoot = p.getInt(KEY_BOOT, -1);
        if (lastBoot != bootCount) {
            // 本次开机的第一次连接
            p.edit().putInt(KEY_BOOT, bootCount)
                    .putLong(KEY_FIRST, now).apply();
            return true;
        }
        // 同一次开机的后续重连：只允许开机初期的补拉
        long first = p.getLong(KEY_FIRST, -1L);
        if (first < 0) return true;
        long delta = now - first;
        return delta >= 0 && delta < RETRY_WINDOW_MS;
    }
}
