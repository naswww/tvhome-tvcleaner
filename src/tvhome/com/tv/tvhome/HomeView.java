package com.tv.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简电视桌面。
 *
 * 不用 RecyclerView / AndroidX / 网络，全部 Canvas 绘制。
 * 1GB 级电视上常驻 PSS 约 33MB（当贝桌面是 115MB）。
 *
 * 交互：
 *   方向键   移动焦点
 *   OK       启动应用
 *   BACK     桌面不退出
 *   长按 OK / 菜单键
 *       · 常用页 → 进入「调整位置」模式
 *       · 全部页 → 加入 / 移出常用
 *   调整位置模式：
 *       方向键  与相邻卡片换位
 *       OK      保存并退出
 *       BACK    还原并退出
 *       菜单键  把当前应用移出常用
 */
final class HomeView extends View {

    private static final String TAG = "TVHome";

    private static final int REGION_GRID = 0;
    private static final int REGION_DOCK = 1;

    /** 常用页最多放几格（5 列 × 3 行）。 */
    private static final int FAV_MAX = 15;

    private static final String PREFS = "tvhome";
    private static final String KEY_FAV = "fav_order";
    private static final String KEY_CUSTOM = "fav_customized";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    /** 全部可启动应用。 */
    private final List<AppRepo.App> allApps = new ArrayList<AppRepo.App>();
    /** 常用应用包名顺序，持久化。 */
    private final List<String> favOrder = new ArrayList<String>();
    /** 当前网格实际显示的应用。 */
    private final List<AppRepo.App> gridApps = new ArrayList<AppRepo.App>();
    private final List<AppRepo.App> dockApps = new ArrayList<AppRepo.App>();

    private boolean showAll = false;      // false=常用  true=全部
    private boolean favCustom = false;    // 用户是否自定义过

    // 调整位置模式
    private boolean editMode = false;
    private final List<String> editSnapshot = new ArrayList<String>();

    private int region = REGION_GRID;
    private int focusGrid = 0;
    private int focusDock = 0;
    private int scrollRow = 0;

    // 长按检测：用定时器而非按键重复事件，真实遥控器与注入事件都可靠
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean okDown = false;
    private boolean okLongHandled = false;
    private final Runnable okLongTask = new Runnable() {
        @Override
        public void run() {
            if (okDown && !okLongHandled) {
                okLongHandled = true;
                onLongPress();
            }
        }
    };

    private int cols = 5;
    private float unit = 1f;
    private float cardW, cardH, gap, pad, headerH, dockH;
    private int lastW, lastH;

    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.getDefault());
    private String clockText = "";
    private long lastClockTick = 0L;
    private String memText = "";
    private long lastMemTick = 0L;

    HomeView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackground(null);
        pt.setTypeface(Typeface.DEFAULT);
        loadFav();
        reload(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(okLongTask);
        okDown = false;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------ persistence ---

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadFav() {
        favOrder.clear();
        favCustom = prefs().getBoolean(KEY_CUSTOM, false);
        String s = prefs().getString(KEY_FAV, "");
        if (s != null && s.length() > 0) {
            for (String line : s.split("\n")) {
                String t = line.trim();
                if (t.length() > 0) favOrder.add(t);
            }
        }
    }

    private void saveFav() {
        StringBuilder sb = new StringBuilder();
        for (String pk : favOrder) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(pk);
        }
        prefs().edit()
                .putString(KEY_FAV, sb.toString())
                .putBoolean(KEY_CUSTOM, favCustom)
                .apply();
    }

    // ------------------------------------------------------------ data ----

    void reload(boolean rescan) {
        Context c = getContext();
        dockApps.clear();
        dockApps.addAll(AppRepo.quickBar(c));

        allApps.clear();
        allApps.addAll(AppRepo.scan(c, c.getPackageName()));

        Map<String, AppRepo.App> byPkg = new HashMap<String, AppRepo.App>();
        for (AppRepo.App a : allApps) byPkg.put(a.pkg, a);

        if (!favCustom) {
            // 首次运行：默认常用 = 视频应用优先 + 顺序补足
            favOrder.clear();
            if (byPkg.containsKey("com.youku.phone")) favOrder.add("com.youku.phone");
            for (AppRepo.App a : allApps) {
                if (favOrder.size() >= FAV_MAX) break;
                if (!favOrder.contains(a.pkg)) favOrder.add(a.pkg);
            }
        } else {
            // 过滤掉已卸载的
            List<String> keep = new ArrayList<String>();
            for (String pk : favOrder) {
                if (byPkg.containsKey(pk)) keep.add(pk);
            }
            if (keep.size() != favOrder.size()) {
                favOrder.clear();
                favOrder.addAll(keep);
                saveFav();
            }
        }

        gridApps.clear();
        if (showAll) {
            gridApps.addAll(allApps);
        } else {
            for (String pk : favOrder) {
                AppRepo.App a = byPkg.get(pk);
                if (a != null) gridApps.add(a);
            }
        }

        if (focusGrid >= gridApps.size()) focusGrid = Math.max(0, gridApps.size() - 1);
        scrollRow = 0;
        invalidate();
    }

    void toggleMode() {
        showAll = !showAll;
        focusGrid = 0;
        scrollRow = 0;
        region = REGION_GRID;
        editMode = false;
        reload(false);
        toast(showAll ? "全部应用 · 长按OK 加入常用" : "常用应用 · 长按OK 调整位置");
    }

    /** 按 HOME 键或重新进入桌面时回到常用首页。 */
    void goHome() {
        showAll = false;
        editMode = false;
        region = REGION_GRID;
        focusGrid = 0;
        focusDock = 0;
        scrollRow = 0;
        reload(true);
        requestFocus();
    }

    // ----------------------------------------------------------- layout ----

    private void measureLayout(int w, int h) {
        if (w == lastW && h == lastH) return;
        lastW = w;
        lastH = h;
        unit = h / 1080f;
        pad = 56 * unit;
        headerH = 96 * unit;
        dockH = 168 * unit;
        gap = 18 * unit;
        cols = 5;
        cardW = (w - pad * 2 - gap * (cols - 1)) / cols;
        cardH = 176 * unit;
    }

    private float gridTop() {
        return headerH + 34 * unit + (editMode ? 40 * unit : 0);
    }

    private float gridBottom() {
        return lastH - dockH - 20 * unit;
    }

    private int rowsVisible() {
        float usable = gridBottom() - gridTop();
        int n = (int) Math.floor((usable + gap) / (cardH + gap));
        return Math.max(1, n);
    }

    private int rowCount() {
        return (gridApps.size() + cols - 1) / cols;
    }

    // ------------------------------------------------------------ draw ----

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        measureLayout(w, h);

        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF10161F, 0xFF0A0E14}, null, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        tickClock();
        drawHeader(cv, w);
        if (editMode) drawEditHint(cv, w);
        drawGrid(cv, w, h);
        drawDock(cv, w, h);
    }

    private void tickClock() {
        long now = System.currentTimeMillis();
        if (now - lastClockTick > 1000) {
            lastClockTick = now;
            clockText = hhmm.format(new Date(now));
            postInvalidateDelayed(1000);
        }
        if (now - lastMemTick > 8000) {
            lastMemTick = now;
            memText = readMem();
        }
    }

    private void drawHeader(Canvas cv, int w) {
        pt.setColor(0xFFFFFFFF);
        pt.setTextSize(46 * unit);
        pt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pt.setTextAlign(Paint.Align.LEFT);
        cv.drawText(clockText, pad, 68 * unit, pt);

        pt.setTypeface(Typeface.DEFAULT);
        pt.setTextSize(22 * unit);
        pt.setColor(0xFF8FA3B8);
        cv.drawText(dateFmt.format(new Date()), pad + 128 * unit, 66 * unit, pt);

        pt.setTextAlign(Paint.Align.RIGHT);
        pt.setTextSize(22 * unit);
        pt.setColor(0xFF6F8296);
        cv.drawText(memText, w - pad, 66 * unit, pt);
        pt.setTextAlign(Paint.Align.LEFT);
    }

    /** 调整模式下的操作提示条。 */
    private void drawEditHint(Canvas cv, int w) {
        float y = headerH + 6 * unit;
        float hh = 34 * unit;
        tmpRect.set(pad, y, w - pad, y + hh);
        p.setColor(0xFF3A2E12);
        cv.drawRoundRect(tmpRect, 10 * unit, 10 * unit, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * unit);
        p.setColor(0xFFFFB300);
        cv.drawRoundRect(tmpRect, 10 * unit, 10 * unit, p);
        p.setStyle(Paint.Style.FILL);

        pt.setColor(0xFFFFD54F);
        pt.setTextSize(21 * unit);
        pt.setTextAlign(Paint.Align.CENTER);
        cv.drawText("调整位置中：方向键换位 · OK 保存 · 返回还原 · 菜单键移出常用",
                w / 2f, y + hh * 0.68f, pt);
        pt.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGrid(Canvas cv, int w, int h) {
        if (gridApps.isEmpty()) {
            pt.setColor(0xFF7A8A9A);
            pt.setTextSize(28 * unit);
            pt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(showAll ? "没有可显示的应用" : "常用为空 · 切到应用库长按OK 加入",
                    w / 2f, (gridTop() + gridBottom()) / 2f, pt);
            pt.setTextAlign(Paint.Align.LEFT);
            return;
        }

        int rows = rowsVisible();
        int totalRows = rowCount();

        int frow = focusGrid / cols;
        if (region == REGION_GRID) {
            if (frow < scrollRow) scrollRow = frow;
            if (frow >= scrollRow + rows) scrollRow = frow - rows + 1;
        }
        if (scrollRow > Math.max(0, totalRows - rows)) scrollRow = Math.max(0, totalRows - rows);
        if (scrollRow < 0) scrollRow = 0;

        float top = gridTop();
        int first = scrollRow * cols;
        int last = Math.min(gridApps.size(), (scrollRow + rows) * cols);

        for (int i = first; i < last; i++) {
            int idx = i - first;
            int r = idx / cols, c = idx % cols;
            float x = pad + c * (cardW + gap);
            float y = top + r * (cardH + gap);
            boolean focused = (region == REGION_GRID && focusGrid == i);
            drawCard(cv, gridApps.get(i), x, y, cardW, cardH, focused);
        }

        if (totalRows > rows) {
            float trackH = gridBottom() - gridTop();
            float barH = Math.max(40 * unit, trackH * rows / totalRows);
            float barY = gridTop() + (trackH - barH) * scrollRow / Math.max(1, totalRows - rows);
            p.setColor(0x33FFFFFF);
            tmpRect.set(w - pad * 0.45f, gridTop(), w - pad * 0.3f, gridBottom());
            cv.drawRoundRect(tmpRect, 8, 8, p);
            p.setColor(0xAA4FA3E3);
            tmpRect.set(w - pad * 0.45f, barY, w - pad * 0.3f, barY + barH);
            cv.drawRoundRect(tmpRect, 8, 8, p);
        }
    }

    private void drawCard(Canvas cv, AppRepo.App a, float x, float y, float w, float h, boolean focused) {
        boolean editing = focused && editMode;

        tmpRect.set(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(editing ? 0xFF4A3A10 : (focused ? 0xFF2A4763 : 0xFF1A222C));
        cv.drawRoundRect(tmpRect, 14 * unit, 14 * unit, p);

        if (focused) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * unit);
            p.setColor(editing ? 0xFFFFB300 : 0xFF4FA3E3);
            cv.drawRoundRect(tmpRect, 14 * unit, 14 * unit, p);
            p.setStyle(Paint.Style.FILL);
        }

        float iconSize = 68 * unit;
        float ix = x + (w - iconSize) / 2f;
        float iy = y + 26 * unit;
        drawAppIcon(cv, a, ix, iy, iconSize);

        pt.setColor(focused ? 0xFFFFFFFF : 0xFFC9D6E2);
        pt.setTextSize(23 * unit);
        pt.setTextAlign(Paint.Align.CENTER);
        String label = ellipsize(a.label == null ? a.pkg : a.label, pt, w - 24 * unit);
        cv.drawText(label, x + w / 2f, y + h - 26 * unit, pt);
        pt.setTextAlign(Paint.Align.LEFT);

        // 全部页里已收藏的角标
        if (showAll && favOrder.contains(a.pkg)) {
            p.setColor(0xFF4FA3E3);
            tmpRect.set(x + w - 30 * unit, y + 8 * unit, x + w - 8 * unit, y + 30 * unit);
            cv.drawRoundRect(tmpRect, 6 * unit, 6 * unit, p);
        }
    }

    private void drawAppIcon(Canvas cv, AppRepo.App a, float x, float y, float size) {
        // 懒加载：只为真正画到的图标解码，避免 134 个图标常驻内存
        android.graphics.Bitmap bmp = AppRepo.iconOf(getContext(), a, (int) size);
        if (bmp != null) {
            tmpRect.set(x, y, x + size, y + size);
            try {
                cv.drawBitmap(bmp, null, tmpRect, null);
                return;
            } catch (Exception e) {
                Log.w(TAG, "icon draw fail", e);
            }
        }
        tmpRect.set(x, y, x + size, y + size);
        p.setColor(AppRepo.colorFor(a.pkg));
        cv.drawRoundRect(tmpRect, size * 0.22f, size * 0.22f, p);
        pt.setColor(0xFFFFFFFF);
        pt.setTextSize(size * 0.46f);
        pt.setTextAlign(Paint.Align.CENTER);
        cv.drawText(AppRepo.initial(a.label), x + size / 2f, y + size * 0.68f, pt);
        pt.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDock(Canvas cv, int w, int h) {
        float top = h - dockH + 14 * unit;

        p.setColor(0x22FFFFFF);
        cv.drawRect(pad, top - 10 * unit, w - pad, top - 8 * unit, p);

        int n = dockApps.size() + 1;
        float dw = (w - pad * 2 - gap * (n - 1)) / n;
        if (n < 4) {
            float cap = 420 * unit;
            if (dw > cap) dw = cap;
        }

        for (int i = 0; i < n; i++) {
            float x = pad + i * (dw + gap);
            boolean focused = (region == REGION_DOCK && focusDock == i);
            boolean isLib = (i == dockApps.size());

            tmpRect.set(x, top, x + dw, top + dockH - 46 * unit);
            p.setStyle(Paint.Style.FILL);
            p.setColor(focused ? 0xFF2A4763 : 0xFF161E27);
            cv.drawRoundRect(tmpRect, 12 * unit, 12 * unit, p);
            if (focused) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * unit);
                p.setColor(0xFF4FA3E3);
                cv.drawRoundRect(tmpRect, 12 * unit, 12 * unit, p);
                p.setStyle(Paint.Style.FILL);
            }

            float iconSize = 46 * unit;
            float iy = top + 14 * unit;
            if (isLib) {
                drawLibIcon(cv, x + (dw - iconSize) / 2f, iy, iconSize, focused);
                pt.setColor(focused ? 0xFFFFFFFF : 0xFFC9D6E2);
                pt.setTextSize(20 * unit);
                pt.setTextAlign(Paint.Align.CENTER);
                cv.drawText(showAll ? "常用" : "应用库", x + dw / 2f, top + dockH - 56 * unit, pt);
                pt.setTextAlign(Paint.Align.LEFT);
            } else {
                AppRepo.App a = dockApps.get(i);
                drawAppIcon(cv, a, x + (dw - iconSize) / 2f, iy, iconSize);
                pt.setColor(focused ? 0xFFFFFFFF : 0xFFC9D6E2);
                pt.setTextSize(20 * unit);
                pt.setTextAlign(Paint.Align.CENTER);
                String lb = ellipsize(a.label == null ? a.pkg : a.label, pt, dw - 16 * unit);
                cv.drawText(lb, x + dw / 2f, top + dockH - 56 * unit, pt);
                pt.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void drawLibIcon(Canvas cv, float x, float y, float size, boolean focused) {
        p.setColor(focused ? 0xFF4FA3E3 : 0xFF3A4A5A);
        tmpRect.set(x, y, x + size, y + size);
        cv.drawRoundRect(tmpRect, size * 0.2f, size * 0.2f, p);
        p.setColor(0xFFEAF2FA);
        float g = size * 0.16f, s = (size - g * 3) / 2f;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                tmpRect.set(x + g + c * (s + g), y + g + r * (s + g),
                        x + g + c * (s + g) + s, y + g + r * (s + g) + s);
                cv.drawRoundRect(tmpRect, s * 0.2f, s * 0.2f, p);
            }
        }
    }

    // ------------------------------------------------------------ keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        return handleKeyDown(keyCode, ev);
    }

    private boolean handleKeyDown(int keyCode, KeyEvent ev) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (editMode) swapFocus(-1); else moveH(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (editMode) swapFocus(1); else moveH(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (editMode) swapFocus(-cols); else moveV(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (editMode) swapFocus(cols); else moveV(1);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!okDown) {
                    okDown = true;
                    okLongHandled = false;
                    ui.removeCallbacks(okLongTask);
                    ui.postDelayed(okLongTask, 550);
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                onMenuKey();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (editMode) cancelEdit();
                return true;

            default:
                return super.onKeyDown(keyCode, ev);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                ui.removeCallbacks(okLongTask);
                okDown = false;
                if (!okLongHandled) {
                    if (editMode) commitEdit(); else activate();
                }
                okLongHandled = false;
                return true;
            default:
                return super.onKeyUp(keyCode, ev);
        }
    }

    /** 长按 OK：常用页进调整模式，全部页切换收藏。 */
    private void onLongPress() {
        if (editMode) return;            // 调整模式里长按无额外语义
        if (region != REGION_GRID) return;
        if (showAll) {
            toggleFavorite();
        } else {
            beginEdit();
        }
    }

    /** 菜单键：调整模式里移出常用，其它情况同长按 OK。 */
    private void onMenuKey() {
        if (editMode) {
            removeCurrent();
        } else {
            onLongPress();
        }
    }

    private void moveH(int d) {
        if (region == REGION_GRID) {
            if (gridApps.isEmpty()) return;
            int n = focusGrid + d;
            if (n >= 0 && n < gridApps.size()) focusGrid = n;
        } else {
            int n = focusDock + d;
            int max = dockApps.size();
            if (n >= 0 && n <= max) focusDock = n;
        }
        invalidate();
    }

    private void moveV(int d) {
        if (region == REGION_GRID) {
            if (d > 0) {
                int n = focusGrid + cols;
                if (n < gridApps.size()) {
                    focusGrid = n;
                } else {
                    region = REGION_DOCK;
                    focusDock = Math.min(focusDock, dockApps.size());
                }
            } else {
                int n = focusGrid - cols;
                if (n >= 0) focusGrid = n;
            }
        } else {
            if (d < 0) {
                region = REGION_GRID;
                if (gridApps.isEmpty()) {
                    region = REGION_DOCK;
                    return;
                }
                focusGrid = Math.min(focusGrid, gridApps.size() - 1);
            }
        }
        invalidate();
    }

    // ------------------------------------------------------- edit mode ----

    private void beginEdit() {
        if (gridApps.isEmpty()) return;
        editMode = true;
        editSnapshot.clear();
        editSnapshot.addAll(favOrder);
        favCustom = true;
        rebuildFavFromGrid();
        saveFav();
        invalidate();
        toast("调整位置：方向键换位 · OK 保存 · 返回还原");
    }

    private void commitEdit() {
        if (!editMode) return;
        editMode = false;
        rebuildFavFromGrid();
        saveFav();
        reload(false);
        toast("已保存常用顺序");
    }

    private void cancelEdit() {
        if (!editMode) return;
        editMode = false;
        favOrder.clear();
        favOrder.addAll(editSnapshot);
        saveFav();
        reload(false);
        toast("已还原");
    }

    private void rebuildFavFromGrid() {
        favOrder.clear();
        for (AppRepo.App a : gridApps) favOrder.add(a.pkg);
    }

    /** 调整模式：与目标位置交换。 */
    private void swapFocus(int d) {
        int i = focusGrid;
        int j = i + d;
        if (j < 0 || j >= gridApps.size()) return;
        if (Math.abs(d) == 1 && (i / cols != j / cols)) return;
        Collections.swap(gridApps, i, j);
        focusGrid = j;
        rebuildFavFromGrid();
        invalidate();
    }

    /** 调整模式：移出常用。 */
    private void removeCurrent() {
        if (!editMode || gridApps.isEmpty()) return;
        int i = focusGrid;
        if (i < 0 || i >= gridApps.size()) return;
        AppRepo.App removed = gridApps.remove(i);
        if (focusGrid >= gridApps.size()) focusGrid = Math.max(0, gridApps.size() - 1);
        rebuildFavFromGrid();
        saveFav();
        invalidate();
        toast("已移出：" + (removed.label == null ? removed.pkg : removed.label));
        if (gridApps.isEmpty()) {
            editMode = false;
            reload(false);
        }
    }

    /** 全部页：加入 / 移出常用。 */
    private void toggleFavorite() {
        if (focusGrid < 0 || focusGrid >= gridApps.size()) return;
        AppRepo.App a = gridApps.get(focusGrid);
        if (favOrder.contains(a.pkg)) {
            favOrder.remove(a.pkg);
            favCustom = true;
            saveFav();
            toast("已移出常用：" + a.label);
        } else {
            if (favOrder.size() >= FAV_MAX) {
                toast("常用已满 " + FAV_MAX + " 个，请先在常用页移出");
                return;
            }
            favOrder.add(a.pkg);
            favCustom = true;
            saveFav();
            toast("已加入常用：" + a.label);
        }
        invalidate();
    }

    // --------------------------------------------------------- activate ----

    private void activate() {
        if (region == REGION_DOCK && focusDock == dockApps.size()) {
            toggleMode();
            return;
        }
        AppRepo.App a;
        if (region == REGION_GRID) {
            if (focusGrid >= gridApps.size()) return;
            a = gridApps.get(focusGrid);
        } else {
            if (focusDock >= dockApps.size()) return;
            a = dockApps.get(focusDock);
        }
        launch(a);
    }

    private void launch(AppRepo.App a) {
        Context c = getContext();
        PackageManager pm = c.getPackageManager();
        try {
            Intent i = AppRepo.launchIntent(a);
            if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
            if (i == null) {
                toast("无法启动 " + (a.label == null ? a.pkg : a.label));
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "launch fail " + a.pkg, e);
            toast("启动失败：" + a.pkg);
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ misc ----

    private String readMem() {
        long availKb = -1, totalKb = -1;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n < 6) {
                n++;
                if (line.startsWith("MemTotal:")) totalKb = parseKb(line);
                else if (line.startsWith("MemAvailable:")) availKb = parseKb(line);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) { }
        }
        if (availKb < 0 || totalKb < 0) return "";
        return String.format(Locale.US, "可用 %d MB / %d MB", availKb / 1024, totalKb / 1024);
    }

    private static long parseKb(String line) {
        try {
            String s = line.replaceAll("[^0-9]", " ");
            s = s.trim();
            int sp = s.indexOf(' ');
            if (sp > 0) s = s.substring(0, sp);
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String ellipsize(String s, Paint paint, float maxW) {
        if (s == null) return "";
        if (maxW <= 0) return s;
        if (paint.measureText(s) <= maxW) return s;
        String ell = "…";
        float ellW = paint.measureText(ell);
        if (ellW > maxW) return "";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (paint.measureText(s, 0, mid) + ellW <= maxW) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ell;
    }
}
