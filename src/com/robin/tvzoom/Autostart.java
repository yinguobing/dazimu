package com.robin.tvzoom;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Size;

/**
 * 开机自启：先等采集卡枚举出来，再打开放大字幕，避免老人看到「无信号」。
 *
 * 电视冷启动时 USB 摄像头不一定马上建好 camera 节点，所以这里用闹钟每 8 秒
 * 探一次，最多探 20 次（约 160 秒）；插上卡（USB_DEVICE_ATTACHED）时也立刻试一次。
 */
final class Autostart {

    private static final String TAG = "tvzoom";

    static final String ACTION_STEP = "com.robin.tvzoom.AUTOSTART_STEP";
    static final String ACTION_TEST = "com.robin.tvzoom.AUTOSTART_TEST";
    static final String EXTRA_STEP = "step";

    private static final int TOTAL_STEPS = 20;
    private static final long INTERVAL_MS = 8000L;

    private Autostart() { }

    /** 采集卡在不在：支持 720x576/480 的广播分辨率，或不是 2560 以上的内置摄像头。 */
    static boolean cardPresent(Context c) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return false;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                StreamConfigurationMap map =
                        cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes == null) continue;
                boolean pal = false;
                boolean qhd = false;
                for (Size s : sizes) {
                    if (s.getWidth() == 720
                            && (s.getHeight() == 576 || s.getHeight() == 480)) {
                        pal = true;
                    }
                    if (s.getWidth() >= 2560) qhd = true;
                }
                if (pal || !qhd) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "probe camera failed", e);
        }
        return false;
    }

    /** 第 step 次探测。 */
    static void step(Context c, int step) {
        if (cardPresent(c)) {
            Log.i(TAG, "autostart: card ready at step " + step);
            start(c);
            return;
        }
        if (step + 1 < TOTAL_STEPS) {
            schedule(c, step + 1);
        } else {
            Log.i(TAG, "autostart: give up, capture card not found");
        }
    }

    static void start(Context c) {
        // 交给 :keys 进程起（那里有系统绑定的无障碍服务，优先级高、不受后台限制）
        try {
            Intent b = new Intent(c, LaunchReceiver.class);
            b.setAction(LaunchReceiver.ACTION_LAUNCH);
            c.sendBroadcast(b);
            Log.i(TAG, "autostart: dispatch to :keys process");
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(c, CameraZoomActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "autostart: start failed", e);
        }
    }

    static void schedule(Context c, int step) {
        if (step < 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, BootReceiver.class);
        i.setAction(ACTION_STEP);
        i.putExtra(EXTRA_STEP, step);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, 200 + step, i, flags);
        long when = System.currentTimeMillis() + INTERVAL_MS;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }
}
