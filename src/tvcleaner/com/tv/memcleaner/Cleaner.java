package com.tv.memcleaner;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TV memory cleaner. Uses the TV's own adbd (127.0.0.1:5555) so commands run
 * as the shell uid - no root, no APK modification, no accessibility service.
 *
 * All shell work is batched into ONE adb round-trip per step: waiting for a
 * separate CLSE/timeout for each command made earlier builds take a minute.
 * Every command ends with "echo __TVC_DONE__", which AdbClient detects.
 */
public final class Cleaner {

    public interface Log { void line(String s); }

    public static final class Status {
        public long totalKb, freeKb, availKb, cachedKb;
        public int procs;
        public boolean mouseAlive;
        public String psText = "";
        public List<String> top = new ArrayList<String>();
    }

    public static final class Result {
        public final boolean ok;
        public final long gainKb;
        public final int stopped;
        public final String message;
        Result(boolean ok, long gainKb, int stopped, String message) {
            this.ok = ok; this.gainKb = gainKb; this.stopped = stopped; this.message = message;
        }
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final String MARKER = "__TVC_DONE__";
    private static final String SPLIT = "__TVC_SPLIT__";

    public static final String MOUSE = "com.tv.remotemouse.v2";
    public static final String SELF = "com.tv.memcleaner";
    public static final String YOUKU = "com.youku.phone";

    /** Never force-stopped. The mouse is a foreground service; keep it here too. */
    private static final Set<String> KEEP = new HashSet<String>(Arrays.asList(
            MOUSE, SELF,
            // 自建桌面：必须保留，否则深度清理会把桌面停掉，
            // 首屏会掉到直播或 FallbackHome。
            "com.tv.tvhome",
            "com.dangbei.TVlauncher",          // 当贝桌面（已停用，留作兜底）
            "com.android.systemui",            // status bar / system UI
            "com.sohu.inputmethod.sogou.tv",   // input method
            "com.android.tv.settings",         // settings
            // 需要保留的两项（示例配置，按机型替换）
            "com.multiscreen.airplay",         // 投屏
            "com.jamdeo.tv.livetv",            // 直播
            "com.yk.tuner"));                  // companion tool

    private static final Pattern APP_UID = Pattern.compile("^u0_a\\d+$");
    private static final Pattern SAFE_PKG = Pattern.compile("^[A-Za-z0-9_.]+$");

    private final Context ctx;
    private final Log log;
    private AdbClient client;

    public Cleaner(Context ctx, Log log) { this.ctx = ctx; this.log = log; }

    // ------------------------------------------------------------------
    // local adb
    // ------------------------------------------------------------------

    public void connect(int handshakeMs) throws Exception {
        close();
        log.line("connecting to " + HOST + ":" + PORT + " ...");
        client = new AdbClient(HOST, PORT, 3000);
        AdbKey key = new AdbKey(ctx);
        log.line("adb key: " + (key.isEmbedded() ? "embedded (trusted)" : "runtime (TV may ask once)"));
        client.handshake(key, handshakeMs);
        log.line("adb handshake OK");
    }

    public void close() {
        if (client != null) { client.close(); client = null; }
    }

    private String shell(String cmd, int timeoutMs) throws Exception {
        if (client == null) throw new IllegalStateException("adb not connected");
        return client.shell(cmd + " ; echo " + MARKER, timeoutMs);
    }

    private static String preview(String s) {
        if (s == null) return "(empty)";
        String p = s.length() > 400 ? s.substring(0, 400) + "..." : s;
        return p.replace('\r', ' ').replace('\n', '|');
    }

    // ------------------------------------------------------------------
    // status: ONE round-trip
    // ------------------------------------------------------------------

    private static long memValue(String meminfo, String key) {
        for (String ln : meminfo.split("\\n")) {
            if (ln.startsWith(key + ":")) {
                String[] p = ln.trim().split("\\s+");
                if (p.length >= 2) {
                    try { return Long.parseLong(p[1]); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return -1;
    }

    private Status readStatus() throws Exception {
        Status st = new Status();
        String all = shell("cat /proc/meminfo ; echo " + SPLIT + " ; ps -A", 20000);
        int pos = all.indexOf(SPLIT);
        String mi = pos >= 0 ? all.substring(0, pos) : all;
        st.psText = pos >= 0 ? all.substring(pos) : all;
        st.totalKb = memValue(mi, "MemTotal");
        st.freeKb = memValue(mi, "MemFree");
        st.availKb = memValue(mi, "MemAvailable");
        st.cachedKb = memValue(mi, "Cached");

        final List<long[]> rows = new ArrayList<long[]>();
        final List<String> names = new ArrayList<String>();
        for (String ln : st.psText.split("\\n")) {
            String[] p = ln.trim().split("\\s+");
            if (p.length < 9) continue;
            st.procs++;
            String name = p[p.length - 1];
            if (name.startsWith(MOUSE)) st.mouseAlive = true;
            try {
                long rss = Long.parseLong(p[4]);
                if (!"root".equals(p[0]) && !"system".equals(p[0])) {
                    rows.add(new long[]{rss});
                    names.add(p[0] + " " + name);
                }
            } catch (NumberFormatException ignored) { }
        }
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Long.compare(rows.get(b)[0], rows.get(a)[0]);
            }
        });
        for (int i = 0; i < idx.size() && i < 3; i++) {
            int k = idx.get(i);
            st.top.add(String.format("%.1fMB %s", rows.get(k)[0] / 1024f, names.get(k)));
        }
        return st;
    }

    public Status query() {
        Status st = new Status();
        try {
            connect(15000);
            st = readStatus();
            log.line("[meminfo] available " + st.availKb / 1024 + "MB, "
                    + st.procs + " procs, mouse " + (st.mouseAlive ? "alive" : "MISSING"));
        } catch (Exception e) {
            log.line("query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally { close(); }
        return st;
    }

    // ------------------------------------------------------------------
    // probe: one harmless, immediately-visible action
    // ------------------------------------------------------------------

    public Result probe() {
        try {
            connect(15000);
            Status before = readStatus();
            String out = shell("sync ; echo 3 > /proc/sys/vm/drop_caches ; echo RC=$?", 20000);
            log.line("  [drop_caches] " + preview(out));
            Thread.sleep(1000);
            Status after = readStatus();
            boolean ok = after.availKb > 0 && !out.toLowerCase().contains("permission denied");
            String msg = "PROBE " + (ok ? "OK" : "FAILED")
                    + " - MemAvailable " + before.availKb / 1024 + "MB -> "
                    + after.availKb / 1024 + "MB";
            log.line(msg);
            return new Result(ok, after.availKb - before.availKb, 0, msg);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.line("ERROR: " + msg);
            return new Result(false, 0, 0, msg);
        } finally { close(); }
    }

    // ------------------------------------------------------------------
    // merged: refresh status + self-test (was buttons 0 and 1)
    // ------------------------------------------------------------------

    public Result refreshProbe() {
        try {
            connect(15000);
            Status before = readStatus();
            log.line("[meminfo] available " + before.availKb / 1024 + "MB, free "
                    + before.freeKb / 1024 + "MB, cached " + before.cachedKb / 1024
                    + "MB, " + before.procs + " procs, mouse "
                    + (before.mouseAlive ? "alive" : "MISSING"));
            for (String s : before.top) log.line("  top: " + s);
            String out = shell("sync ; echo 3 > /proc/sys/vm/drop_caches ; echo RC=$?", 20000);
            log.line("  [drop_caches self-test] " + preview(out));
            Thread.sleep(1000);
            Status after = readStatus();
            long gain = after.availKb - before.availKb;
            boolean ok = after.availKb > 0 && !out.toLowerCase().contains("permission denied");
            String msg = "REFRESH+PROBE " + (ok ? "OK" : "FAILED") + ": available "
                    + before.availKb / 1024 + "MB -> " + after.availKb / 1024
                    + "MB (gain " + (gain >= 0 ? "+" : "") + gain / 1024 + "MB), mouse "
                    + (after.mouseAlive ? "alive" : "MISSING");
            log.line(msg);
            return new Result(ok, gain, 0, msg);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.line("ERROR: " + msg);
            return new Result(false, 0, 0, msg);
        } finally { close(); }
    }

    // ------------------------------------------------------------------
    // boot guard: keep the disabled list from drifting back
    // ------------------------------------------------------------------

    /** 读当前「已停用」清单（一次 adb 往返）。 */
    private Set<String> disabledSet() throws Exception {
        Set<String> out = new HashSet<String>();
        String raw = shell("pm list packages -d", 20000);
        for (String ln : raw.split("\\n")) {
            String s = ln.trim();
            if (s.startsWith("package:")) out.add(s.substring(8).trim());
        }
        return out;
    }

    /**
     * 对比实际禁用清单与基线；fix=true 时把漂移的包重新停用并复验。
     * 只读写 pm 状态，不动进程、不动数据。
     */
    public Guard.Report guardDisabledList(boolean fix) {
        Guard.Report rep = new Guard.Report();
        rep.baselineCount = Guard.BASELINE.length;
        try {
            connect(15000);
            Set<String> now = disabledSet();
            rep.disabledCount = now.size();
            for (String p : Guard.BASELINE) {
                if (!now.contains(p)) rep.missing.add(p);
            }
            for (String p : now) {
                boolean known = false;
                for (String b : Guard.BASELINE) { if (b.equals(p)) { known = true; break; } }
                if (!known) rep.extra.add(p);
            }
            log.line("[guard] baseline " + rep.baselineCount
                    + ", disabled now " + rep.disabledCount
                    + ", missing " + rep.missing.size()
                    + (rep.extra.isEmpty() ? "" : ", extra " + rep.extra.size()));

            if (fix && !rep.missing.isEmpty()) {
                List<String> todo = new ArrayList<String>(rep.missing);
                StringBuilder b = new StringBuilder();
                for (String p : todo) {
                    b.append("pm disable-user --user 0 ").append(p).append(" ; ");
                }
                b.append("sync");
                log.line("  [guard] re-disabling " + todo.size() + " package(s)");
                log.line("  [guard] " + preview(shell(b.toString(), 30000)));

                Set<String> after = disabledSet();
                for (String p : todo) {
                    if (after.contains(p)) rep.fixed.add(p); else rep.failed.add(p);
                }
                rep.missing.clear();
                rep.disabledCount = after.size();
            }
            log.line("[guard] " + rep.toText(Guard.now()).replace('\n', '|'));
            return rep;
        } catch (Exception e) {
            log.line("[guard] ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            rep.failed.add("adb: " + e.getMessage());
            return rep;
        } finally { close(); }
    }

    /**
     * 某些 system uid 的包（如 com.hisense.ai.service）即使已停用，框架仍会按开机白名单
     * 把它拉起来，pm disable-user 和 am force-stop 都无效。这里只做「尽力压制」：
     * 请求系统回收它的内存，并如实返回它是否仍然存活。
     */
    public String suppressUnkillable() {
        try {
            connect(15000);
            StringBuilder b = new StringBuilder();
            for (String p : Guard.UNKILLABLE) {
                b.append("am force-stop ").append(p).append(" ; ");
                b.append("cmd activity send-trim-memory ").append(p).append(" 80 ; ");
            }
            shell(b.toString(), 20000);
            StringBuilder alive = new StringBuilder();
            String ps = shell("ps -A", 20000);
            for (String p : Guard.UNKILLABLE) {
                if (ps.contains(p)) alive.append(p).append(' ');
            }
            String msg = alive.length() == 0
                    ? "system processes: all suppressed"
                    : "system processes still alive (cannot stop without root): " + alive.toString().trim();
            log.line("[suppress] " + msg);
            return msg;
        } catch (Exception e) {
            log.line("[suppress] ERROR " + e.getMessage());
            return "suppress failed: " + e.getMessage();
        } finally { close(); }
    }

    // ------------------------------------------------------------------
    // cleaning
    // ------------------------------------------------------------------

    /** Only third-party (u0_a*) package names are candidates. */
    private Set<String> candidates(String ps, boolean keepYouku) {
        Set<String> out = new LinkedHashSet<String>();
        for (String ln : ps.split("\\n")) {
            String[] p = ln.trim().split("\\s+");
            if (p.length < 9 || !APP_UID.matcher(p[0]).matches()) continue;
            String name = p[p.length - 1];
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (!SAFE_PKG.matcher(name).matches()) continue;
            if (KEEP.contains(name)) continue;
            // Never touch OEM system services: killing e.g. <OEM>.tv.inputs
            // breaks the remote HOME key / return-to-desktop on some Android TVs.
            if (name.startsWith("com.hisense.")) continue;
            if (keepYouku && YOUKU.equals(name)) continue;
            out.add(name);
        }
        return out;
    }

    public Result clean(boolean deep, boolean keepYouku) {
        try {
            connect(15000);
            Status before = readStatus();
            log.line("before: available " + before.availKb / 1024 + "MB, free "
                    + before.freeKb / 1024 + "MB, " + before.procs + " procs");

            // ONE batched shell command: force-stop, kill-all, trim, drop caches,
            // mouse guard, then read the result back. Avoids repeated adb channels.
            StringBuilder b = new StringBuilder();
            int stopped = 0;
            Set<String> pkgs = null;
            if (deep) {
                pkgs = candidates(before.psText, keepYouku);
                log.line("deep candidates: " + pkgs.size());
                for (String pkg : pkgs) {
                    b.append("am force-stop ").append(pkg).append(" ; ");
                    stopped++;
                }
            }
            b.append("am kill-all ; ");
            for (String proc : new String[]{"com.dangbei.TVlauncher", "com.android.systemui",
                    "com.youku.phone:channel"}) {
                b.append("cmd activity send-trim-memory ").append(proc).append(" 80 ; ");
            }
            b.append("sync ; echo 3 > /proc/sys/vm/drop_caches ; sleep 1 ; ");
            b.append("if ps -A | grep -q ").append(MOUSE)
                    .append(" ; then echo MOUSE=alive ; else ")
                    .append("monkey -p ").append(MOUSE)
                    .append(" -c android.intent.category.LAUNCHER 1 >/dev/null ; ")
                    .append("echo MOUSE=restarted ; fi ; ");
            b.append("echo __TVC_AFTER__ ; cat /proc/meminfo | grep -E 'MemFree|MemAvailable|Cached' ; ")
                    .append("echo __TVC_PROCS__=$(ps -A | wc -l) ; echo RC=$?");
            String out = shell(b.toString(), 30000);
            log.line("  [batch] " + preview(out));

            int ai = out.indexOf("__TVC_AFTER__");
            int pi = out.indexOf("__TVC_PROCS__");
            String mem = ai >= 0 ? out.substring(ai, pi > ai ? pi : out.length()) : "";
            long avail = memValue(mem, "MemAvailable");
            int procs = before.procs;
            java.util.regex.Matcher pm = java.util.regex.Pattern
                    .compile("__TVC_PROCS__=\\s*(\\d+)").matcher(out);
            if (pm.find()) {
                try { procs = Integer.parseInt(pm.group(1)); } catch (NumberFormatException ignored) { }
            }
            String mouse = out.contains("MOUSE=restarted") ? "restarted"
                    : (out.contains("MOUSE=alive") ? "alive" : "unknown");
            log.line("  mouse pointer: " + mouse);

            // 复验：v1.0.8 及之前只报「发了多少条 force-stop」，从不确认是否真的停掉。
            int survivors = -1;
            if (deep && pkgs != null && !pkgs.isEmpty()) {
                Status post = readStatus();
                survivors = 0;
                for (String pkg : pkgs) {
                    if (post.psText.contains(pkg)) survivors++;
                }
                log.line("  verify: " + (pkgs.size() - survivors) + "/" + pkgs.size()
                        + " really stopped, " + survivors + " survivor(s)");
            }

            long gain = avail - before.availKb;
            String msg = "CLEAN " + (deep ? "DEEP" : "STANDARD") + ": available "
                    + before.availKb / 1024 + "MB -> "
                    + (avail >= 0 ? avail / 1024 : before.availKb / 1024)
                    + "MB (gain " + (gain >= 0 ? "+" : "") + gain / 1024 + "MB), stopped "
                    + stopped + " app(s)"
                    + (survivors >= 0 ? " [" + (pkgs.size() - survivors) + " verified]" : "")
                    + ", " + before.procs + " -> " + procs + " procs, mouse " + mouse;
            log.line(msg);
            return new Result(true, gain, stopped, msg);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.line("ERROR: " + msg);
            return new Result(false, 0, 0, msg);
        } finally { close(); }
    }

    /**
     * The mouse pointer is the one thing that must survive. Restart it if lost.
     *
     * @param restoreHome true 时，重启鼠标后立刻把 HOME 桌面拉回前台。
     *                    开机守卫必须传 true —— 否则 monkey 会把鼠标自己的界面
     *                    带到前台，抢掉桌面首屏。
     */
    public String ensureMouse(boolean restoreHome) {
        try {
            boolean connected = client != null;
            if (!connected) connect(15000);
            String ps = shell("ps -A | grep " + MOUSE, 15000);
            if (ps.contains(MOUSE)) {
                log.line("mouse pointer: alive");
            } else {
                log.line("mouse pointer: MISSING -> restarting");
                // 注意：am start-foreground-service 会被拒绝
                // （"Requires permission not exported"），MouseService 未导出。
                // 能真正拉起它的是 monkey 启动主界面，由 Activity 带起前台服务。
                log.line("  " + preview(shell(
                        "monkey -p " + MOUSE + " -c android.intent.category.LAUNCHER 1", 20000)));
                sleepQuiet(2500);
                String after = shell("ps -A | grep " + MOUSE, 15000);
                if (after.contains(MOUSE)) log.line("mouse pointer: restarted OK");
                else log.line("mouse pointer: STILL MISSING - 需要人工检查");
                ps = after;
                if (restoreHome) {
                    shell("am start -a android.intent.action.MAIN "
                            + "-c android.intent.category.HOME", 15000);
                    log.line("  [guard] home restored after mouse restart");
                }
            }
            if (!connected) close();
            return ps;
        } catch (Exception e) {
            log.line("mouse check failed: " + e.getMessage());
            return "";
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    public String launchYouku() {
        try {
            connect(15000);
            String out = shell("monkey -p " + YOUKU
                    + " -c android.intent.category.LAUNCHER 1", 15000);
            log.line("  [launch youku] " + preview(out));
            return out;
        } catch (Exception e) {
            log.line("launch failed: " + e.getMessage());
            return "";
        } finally { close(); }
    }
}
