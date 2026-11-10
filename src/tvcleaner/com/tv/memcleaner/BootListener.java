package com.tv.memcleaner;

import android.content.Context;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 开机守卫的触发通道。
 *
 * 本机固件（海信 VIDAA / Android 9）只把 BOOT_COMPLETED 投递给系统应用，
 * 第三方应用的静态广播接收器收不到，JobScheduler 的持久化任务重启后也不会恢复
 * （与 TVHome 桌面遇到的是同一个限制，见《开机自启方案.md》）。
 *
 * 可用通道是「已授权的通知监听服务」：系统开机后会主动绑定它。
 * 授权方式（一次性）：
 *   adb shell cmd notification allow_listener com.tv.memcleaner/.BootListener
 *
 * 只在开机后 4 分钟内跑一次，避免使用过程中反复读写包状态。
 */
public class BootListener extends NotificationListenerService {

    private static final String TAG = "TVCleaner";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "guard listener connected");

        // 用系统 BOOT_COUNT 判断「本次开机是否已经跑过」，而不是用 uptime 窗口。
        // 教训：uptime 窗口判断不可靠 —— 实测安装/更新 APK 导致监听器重连时，
        // 守卫会在使用过程中又跑一遍（日志里出现过两次非开机时段的执行）。
        // BOOT_COUNT 每次开机 +1，精确对应「一次开机一次」。
        final Context ctx = getApplicationContext();
        if (!firstRunThisBoot(ctx)) {
            Log.i(TAG, "guard already ran this boot, skip");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Cleaner c = new Cleaner(ctx, new Cleaner.Log() {
                        @Override public void line(String s) { Log.i(TAG, s); }
                    });
                    Guard.Report rep = c.guardDisabledList(true);
                    String text = rep.toText(Guard.now());
                    Guard.saveReport(ctx, text);
                    Log.i(TAG, "guard done: " + text.replace('\n', '|'));
                    // 遥控鼠标（红外模拟鼠标）是这套方案的必需件，开机顺手兜底拉起。
                    // restoreHome=true：拉完鼠标立刻把桌面带回前台，避免抢首屏。
                    c.ensureMouse(true);
                } catch (Throwable t) {
                    Log.e(TAG, "guard failed", t);
                }
            }
        }, "tvcleaner-guard").start();
    }

    /** 本次开机是否还没跑过守卫；顺手把 BOOT_COUNT 记下来。 */
    private boolean firstRunThisBoot(Context ctx) {
        int bootCount = -1;
        try {
            bootCount = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) { }
        if (bootCount < 0) {
            // 老系统取不到 BOOT_COUNT：退回 uptime 判断
            return SystemClock.elapsedRealtime() < 240000L;
        }
        int last = Guard.lastBootCount(ctx);
        if (last == bootCount) return false;
        Guard.saveBootCount(ctx, bootCount);
        return true;
    }
}
