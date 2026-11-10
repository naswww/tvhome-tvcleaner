package com.tv.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 开机自启 + 重试。
 *
 * 背景：本机固件（海信 VIDAA / Android 9）的 ActivityManagerService **不会**在开机后
 * 拉起真正的默认桌面：系统有时停在 com.android.settings/.FallbackHome，
 * 首屏由框架白名单 /system/etc/app_start_when_boot_whitelist.txt 决定
 * （聚好看 / 直播）。
 *
 * 所以第三方桌面只能自己监听 BOOT_COMPLETED 起来。但框架的首屏启动与
 * BOOT_COMPLETED 存在竞态，单次启动会被后来的首屏覆盖，因此这里做
 * 「立即启动 + 5 秒后补一次」两次尝试。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "TVHome";
    private static final String ACTION_RETRY = "com.tv.tvhome.RETRY_START";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_RETRY.equals(action)) {
            if (MainActivity.RESUMED) {
                Log.i(TAG, "retry: already on top, skip");
                return;
            }
            Log.i(TAG, "retry: bring home to front");
            startHome(ctx);
            return;
        }

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.i(TAG, "boot: start home");
        startHome(ctx);
        // 框架首屏可能随后覆盖，补一次
        scheduleRetry(ctx, 5000L, 1001);
        scheduleRetry(ctx, 12000L, 1002);
    }

    private void startHome(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "start home fail", e);
        }
    }

    private void scheduleRetry(Context ctx, long delayMs, int reqCode) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, BootReceiver.class);
            i.setAction(ACTION_RETRY);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Exception e) {
            Log.w(TAG, "schedule retry fail", e);
        }
    }
}
