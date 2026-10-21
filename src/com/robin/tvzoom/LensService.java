package com.robin.tvzoom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 录屏 + 悬浮窗放大条。
 *
 * MediaProjection -> VirtualDisplay -> ImageReader -> 裁取景框 -> 缩放 -> 悬浮窗。
 * 悬浮窗不吃焦点，所以底下的播放器继续播。
 *
 * 硬约束：自己的悬浮窗也会被录进去，所以取景框不能和放大条重叠，否则无限套娃。
 * 这里按「条在顶部就往下让、条在底部就往上让」自动夹取。
 */
public class LensService extends Service {
    private static final String TAG = "TVZoom";
    private static final String CHANNEL = "lens";

    public static final String ACTION_UPDATE = "com.robin.tvzoom.UPDATE";
    public static final String ACTION_CTRL = "com.robin.tvzoom.CTRL";
    private static final String ACTION_CFG = "com.robin.tvzoom.CFG";

    /** 这些包在前台时才显示放大条。 */
    private static final Set<String> PLAYERS = new HashSet<>(Arrays.asList(
            "com.huawei.tvplayer",
            "com.huawei.himovie.tv",
            "com.huawei.huaweivision.cloudlink"
    ));

    /** 给设置界面判断服务是否在跑。 */
    public static volatile boolean RUNNING = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private WindowManager wm;
    private OverlayView overlay;
    private WindowManager.LayoutParams overlayParams;
    private HandlerThread bgThread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private int screenW, screenH, bandW, bandH;
    private Bitmap band;
    private Bitmap cropBmp;
    private int[] rgb;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint border = new Paint();
    private volatile boolean busy;

    private Config cfg;
    private volatile float zoom = 1.6f;
    private volatile float cx = 0.50f;
    private volatile float cy = 0.86f;
    private volatile float alpha = 1.0f;
    private volatile boolean visible = true;
    private volatile boolean bandAtTop = false;

    private volatile boolean suppressed;
    private volatile long hideUntil;

    private long lastProcessed;
    private int frameCount;
    private long lastFpsTs;
    private boolean layoutLogged;
    private int frameIntervalMs = 80;   // 12.5 fps，字幕不需要更快

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(TAG, "projection stopped");
            release();
            stopSelf();
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CTRL.equals(intent.getAction())) {
                String act = intent.getStringExtra("act");
                if ("show".equals(act)) {
                    suppressed = false;
                } else if ("suppress".equals(act)) {
                    suppressed = true;
                } else if ("hide".equals(act)) {
                    hideUntil = System.currentTimeMillis() + intent.getLongExtra("ms", 8000);
                }
                Log.i(TAG, "ctrl " + act + " suppressed=" + suppressed);
                updateOverlayVisibility();
                return;
            }
            SharedPreferences.Editor e = getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit();
            if (intent.hasExtra("zoom")) {
                e.putInt("zoomIndex", nearestZoom(intent.getIntExtra("zoom", 160) / 100f));
            }
            if (intent.hasExtra("cx")) e.putInt("cx", intent.getIntExtra("cx", 50));
            if (intent.hasExtra("cy")) e.putInt("cy", intent.getIntExtra("cy", 86));
            if (intent.hasExtra("alpha")) e.putInt("alpha", intent.getIntExtra("alpha", 100));
            if (intent.hasExtra("pos")) e.putInt("pos", intent.getIntExtra("pos", 1));
            if (intent.hasExtra("band")) e.putInt("bandPct", intent.getIntExtra("band", 12));
            if (intent.hasExtra("visible")) e.putInt("visible", intent.getIntExtra("visible", 1));
            e.apply();
            applyConfig();
        }
    };

    private static int nearestZoom(float z) {
        int best = 0;
        float d = Float.MAX_VALUE;
        for (int i = 0; i < Config.ZOOMS.length; i++) {
            float diff = Math.abs(Float.parseFloat(Config.ZOOMS[i]) - z);
            if (diff < d) {
                d = diff;
                best = i;
            }
        }
        return best;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = Config.load(this);
        loadConfigValues();
    }

    private void loadConfigValues() {
        zoom = cfg.zoom();
        cx = cfg.cx / 100f;
        cy = cfg.cy / 100f;
        alpha = cfg.alpha / 100f;
        visible = cfg.visible;
        bandAtTop = cfg.pos == 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        RUNNING = true;
        if (projection == null && intent != null && intent.hasExtra("resultData")) {
            try {
                startProjection(intent);
            } catch (Throwable t) {
                Log.e(TAG, "startProjection failed", t);
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("resultData");
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "getMediaProjection null");
            stopSelf();
            return;
        }
        projection.registerCallback(projectionCallback, main);

        bgThread = new HandlerThread("lens");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        setupCapture();
        setupOverlay();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE);
        filter.addAction(ACTION_CFG);
        filter.addAction(ACTION_CTRL);
        registerReceiver(configReceiver, filter);
        main.post(visibilityTicker);
        main.post(foregroundTicker);
        Log.i(TAG, "started: band=" + bandH + "px zoom=" + zoom + " pos=" + (bandAtTop ? "top" : "bottom"));
    }

    private void setupCapture() {
        DisplayMetrics dm = new DisplayMetrics();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        bandW = screenW;
        bandH = Math.max(60, Math.round(screenH * cfg.bandPct / 100f));

        reader = ImageReader.newInstance(screenW, screenH, ImageFormat.YUV_420_888, 3);
        virtualDisplay = projection.createVirtualDisplay(
                "tvzoom", screenW, screenH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, bg);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                onFrame(r);
            }
        }, bg);

        synchronized (lock) {
            allocBuffers();
        }
    }

    private void allocBuffers() {
        band = Bitmap.createBitmap(bandW, bandH, Bitmap.Config.ARGB_8888);
        cropBmp = Bitmap.createBitmap(bandW, bandH, Bitmap.Config.ARGB_8888);
        rgb = new int[bandW * bandH];
    }

    private void setupOverlay() {
        overlay = new OverlayView(this);
        overlayParams = new WindowManager.LayoutParams(
                bandW, bandH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = (bandAtTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        overlayParams.alpha = alpha;
        try {
            wm.addView(overlay, overlayParams);
            Log.i(TAG, "overlay added");
        } catch (Exception e) {
            Log.e(TAG, "overlay failed", e);
        }
    }

    /** 配置变了：热更新，只有条高变化才重建缓冲。 */
    private void applyConfig() {
        cfg = Config.load(this);
        loadConfigValues();
        if (screenH > 0) {
            int newH = Math.max(60, Math.round(screenH * cfg.bandPct / 100f));
            if (newH != bandH) {
                synchronized (lock) {
                    bandH = newH;
                    allocBuffers();
                }
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (overlay == null) return;
                overlayParams.width = bandW;
                overlayParams.height = bandH;
                overlayParams.alpha = alpha;
                overlayParams.gravity = (bandAtTop ? Gravity.TOP : Gravity.BOTTOM)
                        | Gravity.CENTER_HORIZONTAL;
                overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
                try {
                    wm.updateViewLayout(overlay, overlayParams);
                } catch (Exception e) {
                    Log.w(TAG, "updateViewLayout failed", e);
                }
            }
        });
        Log.i(TAG, "apply zoom=" + zoom + " cy=" + cy + " band=" + cfg.bandPct + "% pos=" + cfg.pos);
    }

    private boolean hidden() {
        return suppressed || !visible || System.currentTimeMillis() < hideUntil;
    }

    private void updateOverlayVisibility() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) {
                    overlay.setVisibility(hidden() ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    private final Runnable foregroundTicker = new Runnable() {
        @Override
        public void run() {
            String pkg = foregroundPackage();
            if (pkg != null) {
                boolean keep = PLAYERS.contains(pkg);
                if (keep == suppressed) {
                    suppressed = !keep;
                    Log.i(TAG, "fg=" + pkg + " -> " + (keep ? "show" : "suppress"));
                    updateOverlayVisibility();
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private String foregroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 8000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    private final Runnable visibilityTicker = new Runnable() {
        @Override
        public void run() {
            updateOverlayVisibility();
            main.postDelayed(this, 500);
        }
    };

    private void onFrame(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
            if (hidden()) return;
            long now = System.currentTimeMillis();
            if (now - lastProcessed < frameIntervalMs || busy) return;
            lastProcessed = now;
            busy = true;

            synchronized (lock) {
                if (band == null) return;
                int cropW = Math.max(8, Math.round(bandW / zoom)) & ~1;
                int cropH = Math.max(8, Math.round(bandH / zoom)) & ~1;
                int left = clamp(Math.round(cx * screenW - cropW / 2f), 0, screenW - cropW) & ~1;
                int top = Math.round(cy * screenH - cropH / 2f);

                // 不能让取景框压到放大条上，否则录到自己
                if (bandAtTop) {
                    top = Math.max(top, bandH + 2);
                } else {
                    top = Math.min(top, screenH - bandH - 2 - cropH);
                }
                top = clamp(top, 0, screenH - cropH) & ~1;

                convertRoi(image, left, top, cropW, cropH);
                cropBmp.setPixels(rgb, 0, cropW, 0, 0, cropW, cropH);

                Canvas canvas = new Canvas(band);
                canvas.drawBitmap(cropBmp, new Rect(0, 0, cropW, cropH),
                        new Rect(0, 0, bandW, bandH), blit);
            }
            if (overlay != null) overlay.postInvalidate();

            frameCount++;
            if (frameCount % 50 == 0) {
                long ts = System.currentTimeMillis();
                if (lastFpsTs > 0) {
                    Log.i(TAG, "fps=" + (50000f / (ts - lastFpsTs)));
                }
                lastFpsTs = ts;
            }
        } catch (Exception e) {
            Log.w(TAG, "frame error", e);
        } finally {
            if (image != null) image.close();
            busy = false;
        }
    }

    /** 只把取景框那一块 YUV 转成 RGB（BT.601 有限范围）。 */
    private void convertRoi(Image image, int left, int top, int cropW, int cropH) {
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        if (!layoutLogged) {
            layoutLogged = true;
            Log.i(TAG, "planes=" + p.length
                    + " Y(r" + yRow + ",p" + yPix + ") U(r" + uRow + ",p" + uPix
                    + ") V(r" + vRow + ",p" + vPix + ")");
        }
        int idx = 0;
        int halfLeft = left >> 1;
        for (int r = 0; r < cropH; r++) {
            int yy = top + r;
            int yBase = yy * yRow + left * yPix;
            int cBase = yy >> 1;
            int uBase = cBase * uRow + halfLeft * uPix;
            int vBase = cBase * vRow + halfLeft * vPix;
            for (int c = 0; c < cropW; c++) {
                int Y = (yb.get(yBase + c * yPix) & 0xff) - 16;
                if (Y < 0) Y = 0;
                int ci = c >> 1;
                int U = (ub.get(uBase + ci * uPix) & 0xff) - 128;
                int V = (vb.get(vBase + ci * vPix) & 0xff) - 128;
                int yv = 298 * Y;
                int rr = (yv + 409 * V) >> 8;
                int gg = (yv - 208 * V - 100 * U) >> 8;
                int bb = (yv + 517 * U) >> 8;
                rgb[idx++] = 0xff000000
                        | (clamp255(rr) << 16)
                        | (clamp255(gg) << 8)
                        | clamp255(bb);
            }
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "字幕放大", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("字幕放大运行中")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();
    }

    private void release() {
        RUNNING = false;
        main.removeCallbacks(visibilityTicker);
        main.removeCallbacks(foregroundTicker);
        try {
            unregisterReceiver(configReceiver);
        } catch (Exception ignored) {
        }
        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
        if (bgThread != null) bgThread.quitSafely();
        virtualDisplay = null;
        reader = null;
        projection = null;
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    private class OverlayView extends View {
        OverlayView(Context context) {
            super(context);
            border.setColor(Color.argb(70, 255, 255, 255));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            synchronized (lock) {
                if (band != null) {
                    canvas.drawBitmap(band, null, new Rect(0, 0, getWidth(), getHeight()), blit);
                }
            }
            canvas.drawRect(1, 1, getWidth() - 1, getHeight() - 1, border);
        }
    }
}
