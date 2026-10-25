package com.robin.tvzoom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;

/**
 * 用 MediaSession 接收「播放/暂停」这类媒体键，不经过无障碍。
 *
 * USB 按键已改成发 Play/Pause：按下时系统把媒体键路由给最活跃的媒体会话，
 * 我们就在这里接住，然后把大字幕叫到前台。
 */
public class MediaButtonService extends Service {
    private static final String TAG = "tvzoom";
    private static final String CHANNEL = "media_key";

    private MediaSession session;
    private long lastLaunchAt;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "按键监听", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("大字幕")
                .setContentText("等待 USB 按键")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(2, n);

        if (session == null) {
            session = new MediaSession(this, "tvzoom-usb-key");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { launch(); }
                @Override public void onPause() { launch(); }

                @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent e = mediaButtonIntent == null ? null
                            : (KeyEvent) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (e != null && e.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = e.getKeyCode();
                        Log.i(TAG, "mediaKey " + KeyEvent.keyCodeToString(code));
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                                || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                                || code == KeyEvent.KEYCODE_MEDIA_STOP
                                || code == KeyEvent.KEYCODE_HEADSETHOOK) {
                            launch();
                            return true;
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
            });
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            session.setActive(true);
            Log.i(TAG, "MediaButtonService ready");
        }
        return START_STICKY;
    }

    private void launch() {
        long now = System.currentTimeMillis();
        if (now - lastLaunchAt < 800) return;
        lastLaunchAt = now;
        try {
            Intent i = new Intent(this, CameraZoomActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            Log.i(TAG, "media key -> launch");
        } catch (Throwable t) {
            Log.e(TAG, "media key launch failed", t);
        }
    }

    @Override public void onDestroy() {
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
