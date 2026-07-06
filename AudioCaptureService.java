package com.leno.subtitletranslator;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.Log;

public class AudioCaptureService {
    private static final String TAG = "AudioCapture";
    public static final int REQUEST_CODE = 200;
    private MediaProjection projection;
    private AudioRecord recorder;
    private boolean running = false;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    public static void requestPermission(Activity a) {
        if (!isSupported()) return;
        MediaProjectionManager m = (MediaProjectionManager)
            a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        a.startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CODE);
    }

    public boolean init(Activity a, int code, Intent data) {
        if (data == null || !isSupported()) return false;
        MediaProjectionManager m = (MediaProjectionManager)
            a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(code, data);
        return projection != null;
    }

    public boolean start(Callback cb) {
        if (!isSupported() || projection == null) return false;
        try {
            AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();
            int buf = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(buf * 2)
                .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return false;
            running = true;
            recorder.startRecording();
            new Thread(() -> {
                short[] b = new short[buf];
                while (running) {
                    int r = recorder.read(b, 0, b.length);
                    if (r > 0 && cb != null) cb.onData(b, r);
                }
            }).start();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Blocked, use mic: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (projection != null) { projection.stop(); projection = null; }
    }

    public interface Callback { void onData(short[] data, int len); }
}
