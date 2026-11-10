package com.tv.memcleaner;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 开机守卫：防止固件把已经停用的包重新启用。
 *
 * 背景：部分 Android TV 在系统升级或某些动作后，可能会把
 * pm disable-user 过的包重新启用。CleanYourTV 的做法是「记在清单头部」，
 * 这里做成自动化版本：每次开机后对比实际禁用清单与基线，发现漂移就重新停用并记录。
 *
 * BASELINE 是示例清单，请按目标机型的预装应用替换。
 * 如果以后又手工停用了新的包，把它加进 BASELINE 即可。
 */
public final class Guard {

    private Guard() { }

    private static final String PREFS = "tvcleaner_guard";
    private static final String KEY_LAST = "last_report";
    private static final String KEY_BOOT = "last_boot_count";

    public static int lastBootCount(Context ctx) {
        try {
            return prefs(ctx).getInt(KEY_BOOT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void saveBootCount(Context ctx, int bootCount) {
        try {
            prefs(ctx).edit().putInt(KEY_BOOT, bootCount).apply();
        } catch (Throwable ignored) { }
    }

    /** 应该处于「已停用」状态的包。 */
    public static final String[] BASELINE = {
            // 桌面 / 内容
            "com.jamdeo.tv.vod",              // 聚好看
            "com.dangbei.TVlauncher",         // 当贝桌面
            // 广告
            "com.hisense.business.ad",
            "com.hisense.service.ad",
            // 遥测
            "com.hisense.hitv.das",
            "com.hisense.logupload",
            // 预装
            "com.hisense.demonstrate",
            "com.hisense.electronictutorial",
            "com.hisense.autotest",
            "com.hisense.tv.foodmanage",
            "com.hisense.raycodereceiver",
            // 语音 / AI
            "com.keylab.speech.view.vidaa",
            "com.keylab.speech.core.vidaa",
            "com.hisense.ai.service",
            "com.hisense.tv.aivirtualassistant",
            "com.aispeech.dui.dds.tvassistant",
            // 天气 / 通话
            "com.hisense.tv.weather",
            "com.hisense.tv.videochat.social",
            // 蓝牙（本机无蓝牙硬件）
            "com.android.bluetooth",
    };

    /** 已知停不掉、只能接受或反复压制的包（system uid）。 */
    public static final Set<String> UNKILLABLE = new LinkedHashSet<String>();
    static {
        UNKILLABLE.add("com.hisense.ai.service");
    }

    public static final class Report {
        public int baselineCount;
        public int disabledCount;
        public final List<String> missing = new ArrayList<String>();   // 该禁用却没禁用
        public final List<String> fixed = new ArrayList<String>();     // 本次重新禁用成功的
        public final List<String> failed = new ArrayList<String>();    // 重新禁用失败
        public final List<String> extra = new ArrayList<String>();     // 额外被禁用但不在基线里

        public boolean ok() { return missing.isEmpty() && failed.isEmpty(); }

        public String toText(String when) {
            StringBuilder b = new StringBuilder();
            b.append(when).append(" 基线 ").append(baselineCount)
             .append(" / 实际禁用 ").append(disabledCount);
            if (missing.isEmpty() && failed.isEmpty()) {
                b.append("  ✓ 一致");
            } else {
                b.append("  ✗ 漂移 ").append(missing.size()).append(" 个");
            }
            b.append('\n');
            if (!fixed.isEmpty()) b.append("  已重新停用: ").append(join(fixed)).append('\n');
            if (!failed.isEmpty()) b.append("  停用失败: ").append(join(failed)).append('\n');
            if (!missing.isEmpty() && fixed.isEmpty() && failed.isEmpty())
                b.append("  待处理: ").append(join(missing)).append('\n');
            if (!extra.isEmpty()) b.append("  额外停用(非基线): ").append(join(extra)).append('\n');
            return b.toString().trim();
        }

        private static String join(List<String> l) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(shortName(l.get(i)));
            }
            return b.toString();
        }

        /** com.hisense.tv.weather -> hisense.tv.weather，日志短一点 */
        private static String shortName(String p) {
            return p.startsWith("com.") ? p.substring(4) : p;
        }
    }

    public static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static void saveReport(Context ctx, String text) {
        try {
            prefs(ctx).edit().putString(KEY_LAST, text).apply();
        } catch (Throwable ignored) { }
        appendFile(ctx, text);
    }

    /**
     * 同时写一份到外部文件：/sdcard/Android/data/com.tv.memcleaner/files/guard.log
     *
     * 本机固件屏蔽了第三方应用的 logcat 输出（实测 logcat 里搜不到本应用的任何日志），
     * 所以守卫是否真的跑过、修了什么，只能靠这个文件核查：
     *   adb shell cat /sdcard/Android/data/com.tv.memcleaner/files/guard.log
     */
    private static void appendFile(Context ctx, String text) {
        java.io.FileWriter w = null;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File f = new java.io.File(dir, "guard.log");
            if (f.exists() && f.length() > 60000L) {
                // 简单滚动：超限就重来，保留最近一段
                f.delete();
            }
            w = new java.io.FileWriter(f, true);
            w.write(text.replace('\n', ' ') + "\n");
        } catch (Throwable ignored) {
        } finally {
            if (w != null) { try { w.close(); } catch (Throwable ignored) { } }
        }
    }

    public static String lastReport(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_LAST, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
