package com.tv.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用清单：静态固定项 + 系统已安装应用扫描。
 *
 * 设计取舍：
 * - 固定项（遥控鼠标 / 设置 / TVLive）显式写死组件名，因为部分直播应用
 *   的组件没有 LAUNCHER 类目，只能显式
 *   ComponentName 启动，扫描不出来。
 * - 扫描同时收 LEANBACK_LAUNCHER 与普通 LAUNCHER，这样只有普通
 *   LAUNCHER 的手机端应用也能显示出来。
 * - 图标**按需加载**并降采样。全量加载图标会让 PSS 明显上升，
 *   所以只在真正绘制时解码。
 */
final class AppRepo {

    private static final String TAG = "TVHome";

    /** 一个可启动条目。 */
    static final class App {
        final String label;
        final String pkg;
        final String cls;      // 为 null 时按包名 Launch Intent 启动
        final boolean pinned;  // 固定项
        Bitmap icon;           // 懒加载缓存

        App(String label, String pkg, String cls, boolean pinned) {
            this.label = label;
            this.pkg = pkg;
            this.cls = cls;
            this.pinned = pinned;
        }
    }

    private AppRepo() { }

    /** 按需加载并缓存图标；px 为期望边长。 */
    static Bitmap iconOf(Context ctx, App a, int px) {
        if (a.icon != null) return a.icon;
        if (px <= 0) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = null;
            if (a.cls != null) {
                try {
                    d = pm.getActivityIcon(new ComponentName(a.pkg, a.cls));
                } catch (Exception ignored) { }
            }
            if (d == null) {
                Intent i = pm.getLaunchIntentForPackage(a.pkg);
                if (i != null) {
                    try {
                        d = pm.getActivityIcon(i);
                    } catch (Exception ignored) { }
                }
            }
            if (d == null) d = pm.getApplicationIcon(a.pkg);
            a.icon = toBitmap(d, px);
        } catch (Exception e) {
            Log.w(TAG, "icon fail " + a.pkg, e);
        }
        return a.icon;
    }

    /** Drawable -> 指定边长的 Bitmap（已存在且够大时直接缩放，避免整图解码）。 */
    private static Bitmap toBitmap(Drawable d, int px) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && src.getWidth() > 0) {
                if (src.getWidth() == px && src.getHeight() == px) return src;
                try {
                    return Bitmap.createScaledBitmap(src, px, px, true);
                } catch (Exception ignored) { }
            }
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private static String labelOf(PackageManager pm, String pkg, String cls, String fallback) {
        try {
            if (cls != null) {
                CharSequence cs = pm.getActivityInfo(new ComponentName(pkg, cls), 0).loadLabel(pm);
                if (cs != null && cs.length() > 0) return cs.toString();
            }
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Exception ignored) { }
        return fallback;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 底部快捷栏（用户指定：遥控鼠标 / 设置 / TVLive）。
     * 「应用库」是桌面内置项，由 HomeView 追加。
     */
    static List<App> quickBar(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<App>();

        if (installed(pm, "com.tv.remotemouse.v2")) {
            out.add(new App(
                    labelOf(pm, "com.tv.remotemouse.v2", "com.tv.remotemouse.v2.MainActivity", "遥控鼠标"),
                    "com.tv.remotemouse.v2", "com.tv.remotemouse.v2.MainActivity", true));
        }

        String setCls = "com.hisense.systemsettings.MainActivity";
        if (installed(pm, "com.android.tv.settings")) {
            out.add(new App(
                    labelOf(pm, "com.android.tv.settings", setCls, "设置"),
                    "com.android.tv.settings", setCls, true));
        }

        if (installed(pm, "com.jamdeo.tv.livetv")) {
            out.add(new App(
                    labelOf(pm, "com.jamdeo.tv.livetv", "com.jamdeo.tv.livetv.LiveTvActivity", "TVLive"),
                    "com.jamdeo.tv.livetv", "com.jamdeo.tv.livetv.LiveTvActivity", true));
        }

        return out;
    }

    /**
     * 扫描可启动应用，去掉固定项与自身。
     * @param selfPkg 桌面自己的包名
     */
    static List<App> scan(Context ctx, String selfPkg) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> fixed = new HashSet<String>();
        for (App a : quickBar(ctx)) fixed.add(a.pkg);
        fixed.add(selfPkg);
        // 桌面类应用不列进应用库（避免误点回到旧桌面）
        fixed.add("com.dangbei.TVlauncher");
        fixed.add("com.jamdeo.tv.vod");

        List<App> list = new ArrayList<App>();
        Set<String> seen = new HashSet<String>();

        String[] cats = new String[]{
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                Intent.CATEGORY_LAUNCHER
        };
        for (String cat : cats) {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory(cat);
            List<ResolveInfo> ris;
            try {
                ris = pm.queryIntentActivities(probe, 0);
            } catch (Exception e) {
                continue;
            }
            if (ris == null) continue;
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String cls = ri.activityInfo.name;
                if (pkg == null || pkg.equals(selfPkg)) continue;
                if (fixed.contains(pkg)) continue;
                if (!seen.add(pkg)) continue;

                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && pm.getLaunchIntentForPackage(pkg) == null
                            && cls == null) {
                        continue;
                    }
                } catch (Exception ignored) { }

                CharSequence lbl;
                try {
                    lbl = ri.loadLabel(pm);
                } catch (Exception e) {
                    lbl = pkg;
                }
                list.add(new App(lbl == null ? pkg : lbl.toString(), pkg, cls, false));
            }
        }

        Collections.sort(list, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                String x = a.label == null ? "" : a.label.toLowerCase(Locale.getDefault());
                String y = b.label == null ? "" : b.label.toLowerCase(Locale.getDefault());
                return x.compareTo(y);
            }
        });
        return list;
    }

    /** 构造启动 Intent（cls 为空时返回 null，由调用方回退）。 */
    static Intent launchIntent(App a) {
        if (a.cls != null) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_DEFAULT);
            i.setComponent(new ComponentName(a.pkg, a.cls));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        return null;
    }

    /** 首字母兜底显示。 */
    static String initial(String label) {
        if (label == null || label.isEmpty()) return "?";
        return label.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    /** 稳定色相，给无图标应用做色块。 */
    static int colorFor(String key) {
        int h = key == null ? 0 : key.hashCode();
        float hue = Math.abs(h % 360);
        return Color.HSVToColor(new float[]{hue, 0.45f, 0.42f});
    }
}
