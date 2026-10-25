package com.robin.tvzoom;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只做一件事：USB 按键（默认 F3）按下时把大字幕叫到前台。
 *
 * 不做界面读取，不记录任何输入；除 F3 之外的按键一律放行（返回 false）。
 * 无障碍服务由系统绑定，正常情况下不会被后台限制杀掉。
 */
public class OpenKeyService extends AccessibilityService {

    private static final String TAG = "tvzoom";
    /** USB 按键默认发的键。改这个常量即可换键。 */
    private static final int TRIGGER_KEY = KeyEvent.KEYCODE_F3;

    private long lastLaunchAt;
    private static OpenKeyService instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "OpenKeyService connected, trigger=KEYCODE_F3");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要读界面
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /** 无障碍服务是否就绪。服务由系统绑定，借它起界面可以绕过后台启动限制。 */
    static boolean isReady() {
        return instance != null;
    }

    /** 由服务发起启动：App 有系统绑定的服务，后台启动界面不会被拦。 */
    static void trigger() {
        OpenKeyService s = instance;
        if (s != null) s.launch();
    }

    @Override public void onInterrupt() {
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != TRIGGER_KEY) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            launch();
            return true;   // 吃掉这个键，避免再传给别的应用
        }
        return true;
    }

    private void launch() {
        long now = System.currentTimeMillis();
        if (now - lastLaunchAt < 800) return;    // 防连击
        lastLaunchAt = now;
        try {
            Intent i = new Intent(this, CameraZoomActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            Log.i(TAG, "OpenKey triggered");
        } catch (Throwable t) {
            Log.e(TAG, "OpenKey launch failed", t);
        }
    }
}
