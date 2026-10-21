package com.robin.tvzoom;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判断「现在是不是在看电视」：
 *  - 前台是播放器 → 显示放大条
 *  - 前台是设置/桌面/弹窗 → 让开
 *  - 按任意遥控器键 → 临时让开 8 秒（菜单是播放器自己弹的窗时靠这个）
 *
 * 用轮询 getWindows() 而不是只听事件：弹窗关掉后底层 Activity 常常不再上报，
 * 只听事件会卡在「一直让开」的状态。
 *
 * 不消费按键，只旁观。
 */
public class AutoHideService extends AccessibilityService {
    private static final String TAG = "TVZoom";

    /** 这些包在前台时显示放大条，其余一律让开。 */
    private static final Set<String> PLAYERS = new HashSet<>(Arrays.asList(
            "com.huawei.tvplayer",
            "com.huawei.himovie.tv",
            "com.huawei.huaweivision.cloudlink"
    ));

    private static final long KEY_HIDE_MS = 8000;
    public static final String ACTION_MAG = "com.robin.tvzoom.MAG";

    private final BroadcastReceiver magReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyMagnification(
                    intent.getFloatExtra("scale", 1f),
                    intent.getIntExtra("cx", 960),
                    intent.getIntExtra("cy", 900));
        }
    };

    private void applyMagnification(float scale, int cx, int cy) {
        MagnificationController mc = getMagnificationController();
        if (mc == null) {
            Log.w(TAG, "no magnification controller");
            return;
        }
        try {
            if (scale <= 1.001f) {
                mc.reset(true);
                Log.i(TAG, "mag reset");
                return;
            }
            mc.setScale(scale, false);
            mc.setCenter(cx, cy, false);
            Log.i(TAG, "mag scale=" + scale + " center=" + cx + "," + cy);
        } catch (Throwable t) {
            Log.e(TAG, "mag failed", t);
        }
    }
    private static final long POLL_MS = 800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int unknownTicks;
    private String lastSent;


    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "a11y connected");
        IntentFilter f = new IntentFilter(ACTION_MAG);
        registerReceiver(magReceiver, f);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(magReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 轮询已经覆盖，这里不用做事
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "key " + event.getKeyCode() + " -> hide " + KEY_HIDE_MS + "ms");
            send("hide", KEY_HIDE_MS);
        }
        return false;   // 不消费，遥控器该怎么用还怎么用
    }

    private String foregroundPackage() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) return null;
        for (AccessibilityWindowInfo w : windows) {
            if (w.isFocused()) {
                String p = packageOf(w);
                if (p != null && !isOwnOverlay(p, w)) return p;
            }
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.isActive()) {
                String p = packageOf(w);
                if (p != null && !isOwnOverlay(p, w)) return p;
            }
        }
        return null;
    }

    /** 放大条自己也是个窗口，别把自己当前台。 */
    private boolean isOwnOverlay(String pkg, AccessibilityWindowInfo w) {
        return getPackageName().equals(pkg)
                && w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM;
    }

    private String packageOf(AccessibilityWindowInfo w) {
        try {
            if (w.getRoot() == null) return null;
            CharSequence p = w.getRoot().getPackageName();
            return p == null ? null : p.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void send(String act, long ms) {
        String key = act + ":" + ms;
        if (key.equals(lastSent) && !"hide".equals(act)) return;   // 别刷屏
        Log.i(TAG, "a11y -> " + key);
        lastSent = key;
        Intent i = new Intent(LensService.ACTION_CTRL);
        i.setPackage(getPackageName());
        i.putExtra("act", act);
        i.putExtra("ms", ms);
        sendBroadcast(i);
    }

    @Override
    public void onInterrupt() {
    }
}
