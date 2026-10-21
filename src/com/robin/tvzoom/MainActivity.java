package com.robin.tvzoom;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 电视端设置界面。方向键上下选行、左右调值、OK 开始/停止。
 * 参数存 SharedPreferences，服务端热加载。
 */
public class MainActivity extends Activity {
    private static final String TAG = "TVZoom";
    private static final int REQ_CAPTURE = 1001;

    private Config cfg;
    private final TextView[] rows = new TextView[6];
    private int focus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 12, 14));
        root.setPadding(140, 70, 140, 40);

        TextView title = new TextView(this);
        title.setText("字幕放大");
        title.setTextColor(Color.WHITE);
        title.setTextSize(40);
        title.setPadding(0, 0, 0, 34);
        root.addView(title);

        for (int i = 0; i < rows.length; i++) {
            TextView tv = new TextView(this);
            tv.setTextSize(30);
            tv.setPadding(30, 16, 30, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            tv.setLayoutParams(lp);
            rows[i] = tv;
            root.addView(tv);
        }

        TextView hint = new TextView(this);
        hint.setText("↑↓ 选择   ←→ 调整   OK 开始/停止   返回键退出");
        hint.setTextColor(Color.rgb(140, 140, 140));
        hint.setTextSize(22);
        hint.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = 28;
        hint.setLayoutParams(hlp);
        root.addView(hint);

        setContentView(root);
        refresh();
    }

    private void refresh() {
        rows[0].setText(LensService.RUNNING ? "■  停止放大" : "▶  开始放大");
        rows[1].setText("放大倍数        " + Config.ZOOMS[cfg.zoomIndex] + "×");
        rows[2].setText("字幕条位置      " + (cfg.pos == 0 ? "顶部" : "底部"));
        rows[3].setText("字幕条高度      " + cfg.bandPct + "%");
        rows[4].setText("取景框高度      " + cfg.cy + "%");
        rows[5].setText("不透明度        " + cfg.alpha + "%");

        for (int i = 0; i < rows.length; i++) {
            boolean on = i == focus;
            rows[i].setTextColor(on ? Color.WHITE : Color.rgb(190, 190, 190));
            rows[i].setBackgroundColor(on ? Color.rgb(30, 90, 220) : Color.TRANSPARENT);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focus = (focus + rows.length - 1) % rows.length;
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focus = (focus + 1) % rows.length;
                refresh();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjust(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjust(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (focus == 0) toggle();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void adjust(int dir) {
        switch (focus) {
            case 1:
                cfg.zoomIndex = Config.clamp(cfg.zoomIndex + dir, 0, Config.ZOOMS.length - 1);
                break;
            case 2:
                cfg.pos = cfg.pos == 0 ? 1 : 0;
                break;
            case 3:
                cfg.bandPct = Config.clamp(cfg.bandPct + dir * 2, 8, 30);
                break;
            case 4:
                cfg.cy = Config.clamp(cfg.cy + dir * 2, 20, 98);
                break;
            case 5:
                cfg.alpha = Config.clamp(cfg.alpha + dir * 10, 30, 100);
                break;
            default:
                return;
        }
        cfg.save(this);
        refresh();
        if (LensService.RUNNING) {
            sendBroadcast(new Intent(LensService.ACTION_UPDATE));
        }
    }

    private void toggle() {
        if (LensService.RUNNING) {
            stopService(new Intent(this, LensService.class));
            finish();
            return;
        }
        cfg.save(this);
        startForegroundService(new Intent(this, LensService.class));
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Log.e(TAG, "cannot request projection", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "onActivityResult res=" + resultCode + " data=" + (data != null));
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, LensService.class);
            svc.putExtra("resultCode", resultCode);
            svc.putExtra("resultData", data);
            startForegroundService(svc);
        } else {
            stopService(new Intent(this, LensService.class));
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = Config.load(this);
        refresh();
    }
}
