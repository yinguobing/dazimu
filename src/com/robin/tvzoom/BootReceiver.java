package com.robin.tvzoom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.util.Log;

/** 开机 / 插卡 / 闹钟探测的入口。 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Log.i("tvzoom", "boot completed, probing capture card");
            Autostart.step(c, 0);
            return;
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
            Log.i("tvzoom", "usb attached, probing capture card");
            Autostart.step(c, 0);
            return;
        }
        if (Autostart.ACTION_STEP.equals(a)) {
            Autostart.step(c, intent.getIntExtra(Autostart.EXTRA_STEP, 99));
            return;
        }
        if (Autostart.ACTION_TEST.equals(a)) {
            Autostart.start(c);
        }
    }
}
