package com.robin.tvzoom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 运行在 :keys 进程（和无障碍服务同进程）。
 *
 * 这个进程有系统绑定的无障碍服务，优先级高，从这里启动界面上限更高：
 * 相机重连把主进程拖住的时候，也能把大字幕叫到前台。
 */
public class LaunchReceiver extends BroadcastReceiver {

    static final String ACTION_LAUNCH = "com.robin.tvzoom.LAUNCH";

    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null || !ACTION_LAUNCH.equals(intent.getAction())) return;
        Intent i = new Intent(c, CameraZoomActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
            Log.i("tvzoom", "LaunchReceiver started activity");
        } catch (Throwable t) {
            Log.e("tvzoom", "LaunchReceiver failed", t);
        }
    }
}
