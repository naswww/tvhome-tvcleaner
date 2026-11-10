package com.tv.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 开机自启（JobScheduler 持久化任务）。
 *
 * 为什么不用 BOOT_COMPLETED：本机固件（海信 VIDAA / Android 9）只把
 * BOOT_COMPLETED 投递给预装/系统应用，第三方应用的静态接收器完全收不到广播
 * （已实测：开机日志里只有 uid=1000 的应用有 am_proc_start，我们的包一条都没有；
 * 手动 am broadcast 也不会进广播队列）。所以监听广播这条路在本机走不通。
 *
 * JobScheduler 的 setPersisted(true) 任务会把 JobStatus 写入
 * /data/system/job/jobs.xml，开机后由 JobSchedulerService 重新装载并调度；
 * 而它的 earliestRunTimeElapsed 是按「开机后毫秒数」存的原始值，重启后
 * elapsedRealtime 归零，于是任务立刻到期执行——正好用来抢首屏。
 *
 * 平时用 arm() 把任务推迟 30 分钟重新布防，避免使用过程中被拉回桌面；
 * 开机后 3 分钟窗口内才真正抢前台，窗口外一律不动，防止看视频时被打断。
 */
public class BootJobService extends JobService {

    private static final String TAG = "TVHome";

    private static final int BASE_ID = 4201;
    /** 开机后多久之内允许抢首屏。 */
    private static final long BOOT_WINDOW_MS = 180000L;
    /** 平时布防的延迟：足够长，使用期间不会触发。 */
    private static final long ARM_DELAY_MS = 1800000L;
    /** 开机补刀间隔。 */
    private static final long RETRY_DELAY_MS = 6000L;

    /** 每次桌面回到前台时调用，把持久化任务重新布防到下一次开机。 */
    static void arm(Context ctx) {
        schedule(ctx, BASE_ID, ARM_DELAY_MS);
    }

    private static void schedule(Context ctx, int jobId, long latencyMs) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(jobId,
                    new ComponentName(ctx, BootJobService.class))
                    .setPersisted(true)
                    .setMinimumLatency(latencyMs);
            js.schedule(b.build());
        } catch (Throwable t) {
            Log.w(TAG, "job schedule fail id=" + jobId, t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        long up = SystemClock.elapsedRealtime();
        boolean inWindow = up < BOOT_WINDOW_MS;
        Log.i(TAG, "job: id=" + p.getJobId() + " up=" + up
                + " resumed=" + MainActivity.RESUMED + " window=" + inWindow);

        if (inWindow && !MainActivity.RESUMED) {
            Log.i(TAG, "job: bring home to front");
            startHome();
        }
        // 开机窗口内再补两次，压过框架首屏（LiveTv）的启动
        if (inWindow && p.getJobId() < BASE_ID + 2) {
            schedule(this, p.getJobId() + 1, RETRY_DELAY_MS);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return true;
    }

    private void startHome() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "job start home fail", t);
        }
    }
}
