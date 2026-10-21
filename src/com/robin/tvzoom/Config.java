package com.robin.tvzoom;

import android.content.Context;
import android.content.SharedPreferences;

/** 运行参数，存在 SharedPreferences 里，服务和设置界面共用。 */
public class Config {
    public static final String PREFS = "tvzoom";

    public static final String[] ZOOMS = {"1.0", "1.2", "1.4", "1.6", "1.8", "2.0", "2.5", "3.0"};

    public int zoomIndex = 7;   // 3.0x
    public int pos = 1;         // 0=顶部 1=底部
    public int bandPct = 20;    // 放大条占屏高百分比
    public int cy = 10;         // 取景框中心，% 是离画面底部的距离
    public int cx = 50;
    public int alpha = 100;
    public boolean visible = true;

    public float zoom() {
        return Float.parseFloat(ZOOMS[zoomIndex]);
    }

    public static Config load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config cfg = new Config();
        cfg.zoomIndex = p.getInt("zoomIndex", cfg.zoomIndex);
        cfg.pos = p.getInt("pos", cfg.pos);
        cfg.bandPct = p.getInt("bandPct", cfg.bandPct);
        cfg.cy = p.getInt("cy", cfg.cy);
        cfg.cx = p.getInt("cx", cfg.cx);
        cfg.alpha = p.getInt("alpha", cfg.alpha);
        cfg.visible = p.getBoolean("visible", true);
        if (cfg.zoomIndex < 0 || cfg.zoomIndex >= ZOOMS.length) cfg.zoomIndex = 3;
        cfg.bandPct = clamp(cfg.bandPct, 8, 30);
        cfg.cy = clamp(cfg.cy, 0, 100);
        cfg.cx = clamp(cfg.cx, 20, 80);
        cfg.alpha = clamp(cfg.alpha, 30, 100);
        return cfg;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("zoomIndex", zoomIndex)
                .putInt("pos", pos)
                .putInt("bandPct", bandPct)
                .putInt("cy", cy)
                .putInt("cx", cx)
                .putInt("alpha", alpha)
                .putBoolean("visible", visible)
                .apply();
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
