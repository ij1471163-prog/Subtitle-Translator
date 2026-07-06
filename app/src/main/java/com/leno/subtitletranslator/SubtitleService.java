package com.leno.subtitletranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;

public class SubtitleService extends Service {

    private static final String CHANNEL_ID  = "subtitle_ch";
    private static final int    NOTIF_ID    = 1001;
    public  static final String ACTION_STOP = "com.leno.subtitletranslator.STOP";

    // Backoff: كلما طال السكوت زادت الاستراحة
    // 0 → 1s → 2s → 5s → 15s → 30s → 60s
    private static final long[] BACKOFF = {0, 1000, 2000, 5000, 15000, 30000, 60000};
    private int silence = 0;

    private WindowManager        wm;
    private TextView             overlay;
    private SpeechRecognizer     recognizer;
    private PowerManager.WakeLock wakeLock;
    private final Handler        handler = new Handler(Looper.getMainLooper());
    private boolean              running = false;
    private boolean              recognizerBusy = false;

    private String src = "en-US";
    private String tgt = "ar";

    // إغلاق الشاشة = إيقاف فوري (توفير بطارية)
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        src = p.getString(MainActivity.KEY_SOURCE_LANG, "en-US");
        tgt = p.getString(MainActivity.KEY_TARGET_LANG, "ar");

        // WakeLock خفيف — CPU فقط بدون شاشة
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ST::lock");
        wakeLock.acquire(60 * 60 * 1000L); // max ساعة

        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        createChannel();
        startForeground(NOTIF_ID, buildNotif());
        addOverlay();

        running = true;
        handler.post(this::initRecognizer);
    }

    // ── SpeechRecognizer ────────────────────────────────────────

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            show("التعرف الصوتي غير متاح");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);
        listen();
    }

    private void listen() {
        if (!running || recognizerBusy) return;
        recognizerBusy = true;

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, src);
        // لا نطلب PARTIAL_RESULTS = أقل استهلاك CPU
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        try { recognizer.startListening(i); }
        catch (Exception e) { recognizerBusy = false; scheduleNext(); }
    }

    private void scheduleNext() {
        if (!running) return;
        int idx = Math.min(silence, BACKOFF.length - 1);
        handler.postDelayed(this::listen, BACKOFF[idx]);
    }

    private final RecognitionListener listener = new RecognitionListener() {

        @Override
        public void onResults(Bundle r) {
            recognizerBusy = false;
            ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (m != null && !m.isEmpty() && !m.get(0).trim().isEmpty()) {
                silence = 0; // كلام = reset backoff
                String text = m.get(0);
                TranslationHelper.translateAsync(text, src, tgt, translated -> show(translated));
            } else {
                silence = Math.min(silence + 1, BACKOFF.length - 1);
            }
            scheduleNext();
        }

        @Override
        public void onError(int e) {
            recognizerBusy = false;
            if (e == SpeechRecognizer.ERROR_NO_MATCH
             || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                silence = Math.min(silence + 1, BACKOFF.length - 1);
            } else if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // انتظر أطول لما busy
                handler.postDelayed(this::resetAndListen, 2000);
                return;
            } else {
                silence = 0;
            }
            scheduleNext();
        }

        private void resetAndListen() {
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) {}
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(SubtitleService.this);
            recognizer.setRecognitionListener(this);
            recognizerBusy = false;
            listen();
        }

        @Override public void onPartialResults(Bundle b) {}
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onEndOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEvent(int t, Bundle b) {}
    };

    // ── Overlay ──────────────────────────────────────────────────

    private void addOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(18f);
        overlay.setGravity(Gravity.CENTER);
        overlay.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setPadding(20, 8, 20, 8);
        overlay.setMaxLines(2);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = 120;
        wm.addView(overlay, lp);
    }

    private void show(String text) {
        handler.post(() -> { if (overlay != null) overlay.setText(text); });
    }

    // ── Notification ─────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                CHANNEL_ID, "الترجمة", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotif() {
        int f = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stop = PendingIntent.getService(this, 0,
            new Intent(this, SubtitleService.class).setAction(ACTION_STOP), f);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("الترجمة شغالة")
            .setContentText("اضغط إيقاف")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build();
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
