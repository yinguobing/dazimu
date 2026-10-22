package com.robin.tvzoom;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 采集卡进帧 -> GPU 合成：整幅画面 + 底部放大条。
 * 帧来自 USB 采集卡，不经过录屏，所以放大条可以压在字幕上，不存在套娃。
 */
public class CameraZoomActivity extends Activity
        implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "TVZoom";

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vUV = aUV;\n" +
            "}";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUV;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vUV);\n" +
            "}";

    private GLSurfaceView glView;
    private int texId;
    private SurfaceTexture texture;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface cameraSurface;

    private int program, aPos, aUV, uTex;
    private FloatBuffer vertexBuf;

    private final Handler main = new Handler(Looper.getMainLooper());

    // 可调参数：zoom、条高占比、取景框中心
    private float zoom = 1.6f;
    private float bandPct = 0.12f;
    private float cy = 0.86f;

    // 采集卡把它自己报成前置摄像头，预览是镜像的，这里翻回来
    private float uFlip = 1f;
    private float vFlip = 1f;
    private int bufW = 1920, bufH = 1080;
    private int wantFps = 0;   // 0 = 不指定
    private android.hardware.camera2.CameraCharacteristics characteristics;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private long frameCount, fpsTs;

    private Config cfg;
    private TextView hint;
    private TextView status;
    private volatile long lastFrameAt;
    private long startedAt;
    private volatile boolean cameraMissing;
    private long lastReopenAt;
    private boolean bandOn = true;
    private final BroadcastReceiver setReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("zoom")) {
                float z = intent.getIntExtra("zoom", 160) / 100f;
                int best = 0;
                float diff = Float.MAX_VALUE;
                for (int i = 0; i < Config.ZOOMS.length; i++) {
                    float d = Math.abs(Float.parseFloat(Config.ZOOMS[i]) - z);
                    if (d < diff) {
                        diff = d;
                        best = i;
                    }
                }
                cfg.zoomIndex = best;
            }
            if (intent.hasExtra("band")) cfg.bandPct = Config.clamp(intent.getIntExtra("band", 12), 8, 30);
            if (intent.hasExtra("cy")) cfg.cy = Config.clamp(intent.getIntExtra("cy", 86), 0, 100);
            applyCfg();
        }
    };

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            if (hint != null) hint.setVisibility(android.view.View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);
        zoom = cfg.zoom();
        bandPct = cfg.bandPct / 100f;
        cy = cfg.cy / 100f;
        bandOn = cfg.visible;
        uFlip = 1f;
        vFlip = -1f;
        bufW = 1920;
        bufH = 1080;

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.setFocusable(false);

        FrameLayout root = new FrameLayout(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(28);
        hint.setPadding(36, 20, 36, 20);
        hint.setBackgroundColor(Color.argb(190, 0, 0, 0));
        hint.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = 30;
        root.addView(hint, hlp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(34);
        status.setPadding(48, 28, 48, 28);
        status.setBackgroundColor(Color.argb(200, 0, 0, 0));
        status.setVisibility(android.view.View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        root.addView(status, slp);

        setContentView(root);
        startedAt = System.currentTimeMillis();
        main.post(statusTicker);

        IntentFilter f = new IntentFilter("com.robin.tvzoom.SET");
        registerReceiver(setReceiver, f);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO}, 7);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    cfg.zoomIndex = Config.clamp(cfg.zoomIndex - 1, 0, Config.ZOOMS.length - 1);
                    applyCfg();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    cfg.zoomIndex = Config.clamp(cfg.zoomIndex + 1, 0, Config.ZOOMS.length - 1);
                    applyCfg();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    cfg.cy = Config.clamp(cfg.cy - 2, 0, 100);   // ↑ 字幕往上走
                    applyCfg();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    cfg.cy = Config.clamp(cfg.cy + 2, 0, 100);   // ↓ 字幕往下走
                    applyCfg();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    cfg.visible = !cfg.visible;
                    applyCfg();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    int[] bands = {8, 10, 12, 16, 20, 24, 30};
                    int next = bands[0];
                    for (int b : bands) {
                        if (b > cfg.bandPct) {
                            next = b;
                            break;
                        }
                    }
                    cfg.bandPct = next;
                    applyCfg();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            String msg = null;
            boolean flowing = lastFrameAt > 0 && now - lastFrameAt < 2500;
            if (flowing) {
                cameraMissing = false;
                msg = null;
            } else if (cameraMissing) {
                msg = "未检测到采集卡，正在重试…";
                reopenCamera();
            } else if (lastFrameAt == 0) {
                if (now - startedAt > 6000) msg = "未检测到信号，请检查采集卡和机顶盒";
            } else if (now - lastFrameAt > 2500) {
                msg = "信号中断，正在重连…";
                reopenCamera();
            }
            if (msg == null) {
                status.setVisibility(android.view.View.GONE);
            } else {
                status.setText(msg);
                status.setVisibility(android.view.View.VISIBLE);
            }
            main.postDelayed(this, 1000);
        }
    };

    /** 采集卡换口 / 掉线后相机设备会失效，这里重新打开。 */
    private void reopenCamera() {
        long now = System.currentTimeMillis();
        if (now - lastReopenAt < 3000) return;
        lastReopenAt = now;
        Log.i(TAG, "reopenCamera (retry)");
        Log.i(TAG, "reopen camera");
        stopAudio();
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) {
                camera.close();
                camera = null;
            }
        } catch (Exception ignored) {
        }
        cameraMissing = false;
        openCamera();
    }

    private void applyCfg() {
        zoom = cfg.zoom();
        bandPct = cfg.bandPct / 100f;
        cy = cfg.cy / 100f;
        bandOn = cfg.visible;
        cfg.save(this);
        hint.setText("倍数 " + Config.ZOOMS[cfg.zoomIndex] + "×   条高 " + cfg.bandPct
                + "%   取景框 " + cfg.cy + "%   " + (bandOn ? "开" : "关"));
        hint.setVisibility(android.view.View.VISIBLE);
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, 2000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "camera permission denied");
        }
    }

    // ---------- GL ----------

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = buildProgram(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        uTex = GLES20.glGetUniformLocation(program, "sTex");

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        texture = new SurfaceTexture(texId);
        texture.setDefaultBufferSize(bufW, bufH);
        texture.setOnFrameAvailableListener(this);
        cameraSurface = new Surface(texture);
        main.post(new Runnable() {
            @Override
            public void run() {
                openCamera();
            }
        });
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (texture == null) return;
        texture.updateTexImage();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);

        // 整幅画面（翻正）
        drawQuad(-1, -1, 1, 1, 0, 0, 1, 1);

        if (!bandOn) return;

        // 底部放大条：取景框在字幕区
        float bh = 2 * bandPct;                 // NDC 高度
        float roiH = bh / zoom;                 // 取景框在源画面里的高度（NDC）
        float roiW = 2f / zoom;                 // 取景框宽度（NDC）

        // 取景框整体夹在画面内：贴到底时中心最多到 1 - roiH/2，不会采到画面外
        float halfH = roiH / 2f;                // NDC
        float cyNdc = 1f - 2f * cy;             // 源画面里中心的 NDC y
        float cyNdcMin = -1f + halfH;
        float cyNdcMax = 1f - halfH;
        if (cyNdc < cyNdcMin) cyNdc = cyNdcMin;
        if (cyNdc > cyNdcMax) cyNdc = cyNdcMax;
        float cxNdc = 0f;

        float sx0 = cxNdc - roiW / 2f, sx1 = cxNdc + roiW / 2f;
        float sy0 = cyNdc - roiH / 2f, sy1 = cyNdc + roiH / 2f;

        // NDC -> UV
        float u0 = (sx0 + 1f) / 2f, u1 = (sx1 + 1f) / 2f;
        float v0 = (sy0 + 1f) / 2f, v1 = (sy1 + 1f) / 2f;

        drawQuad(-1, -1, 1, -1 + bh, u0, v0, u1, v1);
    }

    private void drawQuad(float x0, float y0, float x1, float y1,
                          float u0, float v0, float u1, float v1) {
        if (uFlip < 0) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        if (vFlip < 0) {
            float t = v0;
            v0 = v1;
            v1 = t;
        }
        float[] v = {
                x0, y0, u0, v0,
                x1, y0, u1, v0,
                x0, y1, u0, v1,
                x1, y1, u1, v1,
        };
        vertexBuf = ByteBuffer.allocateDirect(v.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuf.put(v).position(0);

        vertexBuf.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        vertexBuf.position(2);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, vertexBuf);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    // ---------- camera ----------

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        lastFrameAt = System.currentTimeMillis();
        cameraMissing = false;
        frameCount++;
        long now = System.currentTimeMillis();
        if (fpsTs == 0) fpsTs = now;
        if (now - fpsTs >= 2000) {
            Log.i(TAG, "cam fps=" + (frameCount * 1000f / (now - fpsTs)));
            frameCount = 0;
            fpsTs = now;
        }
        glView.requestRender();
    }

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            // 相机 ID 每次开机可能变：采集卡支持 720x576/720x480 这类广播分辨率，
            // 内置摄像头只有 16:9 的几档。按这个特征认卡，不认 ID。
            String id = null;
            String fallback = null;
            for (String cid : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                android.hardware.camera2.params.StreamConfigurationMap map =
                        cc.get(android.hardware.camera2.CameraCharacteristics
                                .SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                android.util.Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                if (sizes == null) continue;
                boolean pal = false;
                boolean qhd = false;
                for (android.util.Size sz : sizes) {
                    if (sz.getWidth() == 720
                            && (sz.getHeight() == 576 || sz.getHeight() == 480)) {
                        pal = true;
                    }
                    if (sz.getWidth() >= 2560) qhd = true;
                }
                Log.i(TAG, "camera " + cid + " pal=" + pal + " qhd=" + qhd + " sizes=" + sizes.length);
                if (pal) {
                    id = cid;
                    break;
                }
                if (!qhd && fallback == null) fallback = cid;
            }
            if (id == null) id = fallback;
            if (id == null) {
                Log.e(TAG, "no capture card found");
                cameraMissing = true;
                return;
            }
            characteristics = cm.getCameraCharacteristics(id);
            Log.i(TAG, "open camera " + id);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camera = c;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    Log.w(TAG, "camera disconnected");
                    c.close();
                    camera = null;
                    session = null;
                    reopenCamera();
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    Log.e(TAG, "camera error " + error);
                    c.close();
                    camera = null;
                    session = null;
                    reopenCamera();
                }
            }, main);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            cameraMissing = true;
        }
    }

    private void startSession() {
        try {
            camera.createCaptureSession(Collections.singletonList(cameraSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            cameraMissing = false;
                            try {
                                CaptureRequest.Builder b =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(cameraSurface);
                                if (characteristics != null) {
                                    android.util.Range<Integer>[] ranges =
                                            characteristics.get(android.hardware.camera2.CameraCharacteristics
                                                    .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                                    StringBuilder sb = new StringBuilder();
                                    if (ranges != null) {
                                        for (android.util.Range<Integer> r : ranges) sb.append(r).append(" ");
                                    }
                                    Log.i(TAG, "fps ranges: " + sb);
                                    // 默认挑支持的最高帧率（USB3 上采集卡会给 50fps）
                                    int target = wantFps;
                                    if (target <= 0 && ranges != null) {
                                        for (android.util.Range<Integer> r : ranges) {
                                            if (r.getUpper() > target) target = r.getUpper();
                                        }
                                    }
                                    if (target > 0) {
                                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                new android.util.Range<>(target, target));
                                        Log.i(TAG, "request fps " + target);
                                    }
                                }
                                s.setRepeatingRequest(b.build(), null, main);
                                Log.i(TAG, "session started");
                                startAudio();
                            } catch (Exception e) {
                                Log.e(TAG, "request failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            Log.e(TAG, "session failed");
                        }
                    }, main);
        } catch (Exception e) {
            Log.e(TAG, "createSession failed", e);
        }
    }

    /** 采集卡把 HDMI 音频转成 USB 音频，这里录进来再从喇叭放出去。 */
    private void startAudio() {
        if (audioRunning) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no RECORD_AUDIO, skip audio");
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo usb = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    usb = d;
                }
            }
            Log.i(TAG, "usb audio in: " + usb);

            int rate = 48000;
            int minBuf = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, 16384);

            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();
            if (usb != null) audioRecord.setPreferredDevice(usb);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioRecord.startRecording();
            audioTrack.play();
            audioRunning = true;
            audioThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    long lastLog = 0;
                    while (audioRunning) {
                        int n = audioRecord.read(buf, 0, buf.length);
                        if (n > 0) {
                            audioTrack.write(buf, 0, n);
                            long now = System.currentTimeMillis();
                            if (now - lastLog > 3000) {
                                lastLog = now;
                                long sum = 0;
                                for (int i = 0; i + 1 < n; i += 2) {
                                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                                    sum += Math.abs(v);
                                }
                                Log.i(TAG, "audio rms=" + (sum / (n / 2)));
                            }
                        }
                    }
                }
            });
            audioThread.start();
            Log.i(TAG, "audio started");
        } catch (Exception e) {
            Log.e(TAG, "audio start failed", e);
        }
    }

    private void stopAudio() {
        audioRunning = false;
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception ignored) {
        }
        audioRecord = null;
        audioTrack = null;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(statusTicker);
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(setReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        lastFrameAt = 0;
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startedAt = System.currentTimeMillis();
        lastFrameAt = 0;
        cameraMissing = false;
        glView.onResume();
    }
}
