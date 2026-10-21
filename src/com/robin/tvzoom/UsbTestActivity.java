package com.robin.tvzoom;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Collections;

/** 插上 USB 采集卡后，用这个 Activity 验证能不能当外置摄像头打开。 */
public class UsbTestActivity extends Activity {
    private static final String TAG = "TVZoom";

    private CameraManager cm;
    private CameraDevice device;
    private SurfaceView view;
    private String cameraId;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new SurfaceView(this);
        setContentView(view);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
        } else {
            dumpAndStart();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            dumpAndStart();
        } else {
            Log.e(TAG, "camera permission denied");
        }
    }

    private void dumpAndStart() {
        try {
            String[] ids = cm.getCameraIdList();
            Log.i(TAG, "cameras: " + String.join(",", ids));
            for (String id : ids) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Log.i(TAG, "camera " + id + " facing=" + facing);
                if (map == null) continue;
                for (int f : map.getOutputFormats()) {
                    Size[] sizes = map.getOutputSizes(f);
                    if (sizes == null) continue;
                    StringBuilder sb = new StringBuilder();
                    for (Size s : sizes) {
                        sb.append(s.getWidth()).append("x").append(s.getHeight()).append(" ");
                    }
                    Log.i(TAG, "  fmt " + f + " -> " + sb);
                }
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    cameraId = id;
                }
            }
            if (cameraId == null && ids.length > 1) cameraId = ids[1];
            Log.i(TAG, "chosen camera: " + cameraId);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    Log.i(TAG, "surfaceCreated valid=" + holder.getSurface().isValid());
                    open();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "dump failed", e);
        }
    }

    private void open() {
        Log.i(TAG, "openCamera " + cameraId);
        try {
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    Log.i(TAG, "camera opened");
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "camera error " + error);
                    camera.close();
                }
            }, handler);
        } catch (SecurityException e) {
            Log.e(TAG, "open security", e);
        } catch (Exception e) {
            Log.e(TAG, "open failed", e);
        }
    }

    private void startPreview() {
        try {
            Surface surface = view.getHolder().getSurface();
            device.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                CaptureRequest.Builder b =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(surface);
                                session.setRepeatingRequest(b.build(), null, handler);
                                Log.i(TAG, "preview started");
                            } catch (Exception e) {
                                Log.e(TAG, "preview failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "session configure failed");
                        }
                    }, handler);
        } catch (Exception e) {
            Log.e(TAG, "createSession failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (device != null) device.close();
        super.onDestroy();
    }
}
