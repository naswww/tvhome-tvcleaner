package com.tv.memcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Two-column TV UI: compact controls on the left, full-height log on the right.
 * Focusing a button (remote) or hovering it (mouse pointer) shows its help text.
 * Every action goes through the TV's own adbd; no root, no APK modification.
 */
public class MainActivity extends Activity {

    private Cleaner cleaner;
    private TextView tvStatus, tvLog, tvHint, tvVersion;
    private ScrollView scrollLog;
    private Button bCheck, bClean, bDeep, bYouku, bMouse, bGuard;
    private CheckBox cbKeepYouku;
    private boolean busy;

    private long totalKb = -1, availKb = -1, freeKb = -1, cachedKb = -1;
    private int procs = -1;
    private boolean mouseAlive;
    private String lastResult = "not run";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvLog = (TextView) findViewById(R.id.tv_log);
        tvHint = (TextView) findViewById(R.id.tv_hint);
        tvVersion = (TextView) findViewById(R.id.tv_version);
        scrollLog = (ScrollView) findViewById(R.id.scroll_log);
        bCheck = (Button) findViewById(R.id.btn_check);
        bClean = (Button) findViewById(R.id.btn_clean);
        bDeep = (Button) findViewById(R.id.btn_deep);
        bYouku = (Button) findViewById(R.id.btn_youku);
        bMouse = (Button) findViewById(R.id.btn_mouse);
        bGuard = (Button) findViewById(R.id.btn_guard);
        cbKeepYouku = (CheckBox) findViewById(R.id.cb_keep_youku);

        cleaner = new Cleaner(this, new Cleaner.Log() {
            @Override public void line(String s) { log(s); }
        });

        // version -> top-left note
        String ver = "?";
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Exception ignored) { }
        tvVersion.setText("版本 v" + ver + " · 本地 ADB 回收，不 root / 不改 APK");

        bindHint(bCheck, "① 刷新状态 / 自检：读取总内存/可用/缓存、进程数、遥控鼠标状态，列出占用最高进程，并试写 drop_caches 确认清理有效。");
        bindHint(bClean, "② 标准清理：am kill-all + 清页缓存 + 请求保留进程释放内存。不动任何 App 的数据与登录。");
        bindHint(bDeep, "③ 深度清理：强停所有第三方后台进程（u0_a*，白名单除外），再执行标准清理。"
                + "停掉的 App 下次打开即恢复。清理后会复验，报告实际停掉几个。");
        bindHint(bYouku, "④ 清理并打开视频应用：先做一次标准清理，然后启动目标视频应用，适合直接开始播放。");
        bindHint(bMouse, "⑤ 恢复遥控鼠标：检查 com.tv.remotemouse.v2 前台服务；若被系统杀掉则立即重启，保证指针可用。");
        bindHint(bGuard, "⑥ 清单校验 / 修复：把当前「已停用」清单与基线（19 个包：聚好看/当贝/广告/遥测/预装/语音/AI/天气/通话）对比，"
                + "发现被固件重新启用的自动重新停用并复验。开机后 4 分钟内会自动跑一次。只改包的启用状态，不动进程和数据。");
        bindHint(cbKeepYouku, "勾选后深度清理保留视频应用进程；不勾选则一起停（之后可重新打开，登录状态保留）。");

        bCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                run("Refresh + self-test", new Runnable() {
                    @Override public void run() {
                        Cleaner.Result r = cleaner.refreshProbe();
                        show(r);
                        readStatus();
                    }
                });
            }
        });
        bClean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm("② 标准清理", "am kill-all + 清页缓存 + 请求保留进程释放内存。\n"
                        + "不动任何第三方 App 的登录/数据，遥控鼠标（前台服务）保留。",
                        new Runnable() { @Override public void run() { doClean(false); } });
            }
        });
        bDeep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm("③ 深度清理（会停掉后台 App）",
                        "强停所有第三方后台进程（只处理 u0_a*，系统服务不动），再执行标准清理。\n\n"
                        + "白名单：遥控鼠标、本工具、桌面、systemui、输入法、设置。\n"
                        + "视频应用是否保留由下方勾选决定。停掉的 App 下次打开即可恢复。",
                        new Runnable() { @Override public void run() { doClean(true); } });
            }
        });
        bYouku.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm("④ 清理并打开视频应用", "先做一次标准清理，然后启动目标视频应用播放。",
                        new Runnable() {
                            @Override public void run() {
                                doClean(false);
                                cleaner.launchYouku();
                            }
                        });
            }
        });
        bMouse.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                run("Restore mouse pointer", new Runnable() {
                    @Override public void run() { cleaner.ensureMouse(false); }
                });
            }
        });
        bGuard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                run("Guard disabled list", new Runnable() {
                    @Override public void run() {
                        Guard.Report rep = cleaner.guardDisabledList(true);
                        log("guard: " + (rep.ok() ? "consistent" : "drift handled"));
                        log("  baseline " + rep.baselineCount
                                + ", disabled now " + rep.disabledCount
                                + ", re-fixed " + rep.fixed.size()
                                + ", failed " + rep.failed.size());
                        Guard.saveReport(MainActivity.this, rep.toText(Guard.now()));
                        cleaner.suppressUnkillable();
                        readStatus();
                    }
                });
            }
        });

        log("TV Memory Cleaner v" + ver + " - local ADB, no root, no APK changes.");
        log("Keeps: remote mouse (" + Cleaner.MOUSE + ") + TVHome + casting + livetv + system essentials.");
        log("Focus a button with the remote, or hover it with the mouse, to see its help.");
        String last = Guard.lastReport(this);
        if (last != null && last.length() > 0) {
            log("");
            log("[last boot guard] " + last.replace('\n', ' '));
        }
        log("");
        refreshStatus();
    }

    private static final int C_FOCUS_TXT = 0xFF0A0F16; // near-black on bright amber
    private static final int C_NORMAL_TXT = 0xFFC9D6E2; // light gray

    private void bindHint(View v, final String text) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) {
                    view.setBackgroundResource(R.drawable.btn_focused);
                    if (view instanceof Button) ((Button) view).setTextColor(C_FOCUS_TXT);
                    else if (view instanceof CheckBox) ((CheckBox) view).setTextColor(C_FOCUS_TXT);
                    tvHint.setText(text);
                } else {
                    view.setBackgroundResource(R.drawable.btn_bg);
                    if (view instanceof Button) ((Button) view).setTextColor(C_NORMAL_TXT);
                    else if (view instanceof CheckBox) ((CheckBox) view).setTextColor(C_NORMAL_TXT);
                }
            }
        });
        v.setOnHoverListener(new View.OnHoverListener() {
            @Override public boolean onHover(View view, MotionEvent event) {
                int a = event.getActionMasked();
                if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) {
                    view.setBackgroundResource(R.drawable.btn_focused);
                    if (view instanceof Button) ((Button) view).setTextColor(C_FOCUS_TXT);
                    else if (view instanceof CheckBox) ((CheckBox) view).setTextColor(C_FOCUS_TXT);
                    tvHint.setText(text);
                } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                    if (!view.isFocused()) {
                        view.setBackgroundResource(R.drawable.btn_bg);
                        if (view instanceof Button) ((Button) view).setTextColor(C_NORMAL_TXT);
                        else if (view instanceof CheckBox) ((CheckBox) view).setTextColor(C_NORMAL_TXT);
                    }
                }
                return false;
            }
        });
    }

    private void readStatus() {
        Cleaner.Status st = cleaner.query();
        totalKb = st.totalKb; availKb = st.availKb; freeKb = st.freeKb;
        cachedKb = st.cachedKb; procs = st.procs; mouseAlive = st.mouseAlive;
        for (String s : st.top) log("  " + s);
        refreshStatus();
    }

    private void doClean(final boolean deep) {
        run(deep ? "Deep clean" : "Standard clean", new Runnable() {
            @Override public void run() {
                final boolean keepYouku = cbKeepYouku.isChecked();
                Cleaner.Result r = cleaner.clean(deep, keepYouku);
                show(r);
            }
        });
    }

    private void show(final Cleaner.Result r) {
        log((r.ok ? "OK   " : "FAIL ") + r.message);
        lastResult = r.message;
        refreshStatus();
    }

    private void refreshStatus() {
        runOnUiThread(new Runnable() {
            @Override public void run() { tvStatus.setText(buildStatus()); }
        });
    }

    private String buildStatus() {
        String mem = availKb < 0 ? "not read yet"
                : "available " + availKb / 1024 + "MB / free " + freeKb / 1024
                  + "MB / cached " + cachedKb / 1024 + "MB / total " + totalKb / 1024 + "MB";
        return "Memory: " + mem + "\n"
                + "Processes: " + (procs < 0 ? "?" : procs)
                + " | mouse: " + (mouseAlive ? "ALIVE" : "?") + " | " + lastResult;
    }

    private void confirm(String title, String msg, final Runnable work) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { run(title, work); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void run(final String title, final Runnable work) {
        if (busy) return;
        busy = true;
        setButtons(false);
        log("=== " + title + " ===");
        new Thread(new Runnable() {
            @Override public void run() {
                try { work.run(); }
                catch (Throwable t) { log("FATAL: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
                finally {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { busy = false; setButtons(true); }
                    });
                }
            }
        }, "tvcleaner-work").start();
    }

    private void setButtons(boolean on) {
        bCheck.setEnabled(on); bClean.setEnabled(on);
        bDeep.setEnabled(on); bYouku.setEnabled(on); bMouse.setEnabled(on);
        bGuard.setEnabled(on);
        cbKeepYouku.setEnabled(on);
    }

    private void log(final String s) {
        Log.i("TVCleaner", s);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                tvLog.append(s + "\n");
                scrollLog.post(new Runnable() {
                    @Override public void run() { scrollLog.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }
}
