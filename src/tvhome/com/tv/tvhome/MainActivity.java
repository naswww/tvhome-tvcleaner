package com.tv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 桌面宿主。极简：单一 View，无 Fragment / 无 ViewPager / 无网络。
 *
 * 作为 HOME 时必须保证「永远有窗口可显示」：即使数据加载失败也画出背景，
 * 否则系统会认为桌面崩溃并可能回退到旧桌面。
 */
public class MainActivity extends Activity {

    private static final String TAG = "TVHome";

    /** 桌面当前是否在前台，供开机重试判断是否需要再拉一次。 */
    static volatile boolean RESUMED = false;

    private HomeView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            view = new HomeView(this);
            setContentView(view);
            view.requestFocus();
        } catch (Throwable t) {
            // 绝不能因为桌面自身异常导致黑屏
            Log.e(TAG, "home init failed", t);
            View fallback = new View(this);
            fallback.setBackgroundColor(0xFF10161F);
            fallback.setFocusable(true);
            setContentView(fallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RESUMED = true;
        if (view != null) {
            // 回到桌面时重新扫描（新装的应用要能出现）
            try {
                view.reload(true);
            } catch (Throwable t) {
                Log.w(TAG, "reload fail", t);
            }
            view.requestFocus();
        }
        // 每次回到桌面都把开机任务重新布防，保证下次上电仍能自启
        try {
            BootJobService.arm(this);
        } catch (Throwable t) {
            Log.w(TAG, "arm boot job fail", t);
        }
    }

    @Override
    protected void onPause() {
        RESUMED = false;
        super.onPause();
    }

    /** 兜底：某些机型遥控器 OK 键会走这里。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (view != null && view.onKeyDown(keyCode, ev)) return true;
        return super.onKeyDown(keyCode, ev);
    }

    @Override
    public void onBackPressed() {
        // HOME 不退出
    }

    /** 作为 HOME 时，按遥控器主页键会回到这里：重置到常用首页。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (view != null) {
            try {
                view.goHome();
            } catch (Throwable t) {
                Log.w(TAG, "goHome fail", t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        view = null;
    }
}
