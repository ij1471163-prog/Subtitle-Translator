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

    private static final String CHANNEL_ID  = "subtitle_channel";
    private static final int    NOTIF_ID    = 1001;
    public  static final String ACTION_STOP = "com.leno.subtitletranslator.STOP";

    // ── Backoff: يزيد كلما طال السكوت ──────────────────────────
    // 0s → 0.5s → 1s → 2s → 5s → 15s → 30s
    // بعد 30 ثانية سكوت يستنى 30 ثانية = توفير 95% بطارية
    private static final long[] BACKOFF_MS = {0, 500, 1000, 2000, 5000, 15000, 30000};
    private int silenceCount = 0;

    // ── State ───────────────────────────────────────────────────
    private WindowManager     windowManager;
    private TextView          subtitleView;
    private SpeechRecognizer  recognizer;
    private PowerManager.WakeLock wakeLock;
    private final Handler     mainHandler = new Handler(Looper.getMainLooper());
    private boolean           running     = false;

    // ── Settings ────────────────────────────────────────────────
    private String sourceLang = "en-US";
    private String targetLang = "ar";

    // ── Screen off receiver ─────────────────────────────────────
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // الشاشة أغلقت = المستخدم مو شايف الترجمة = أوقف
                stopSelf();
            }
        }
    };

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();

        // تحميل الإعدادات
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        sourceLang = prefs.getString(MainActivity.KEY_SOURCE_LANG, "en-US");
        targetLang = prefs.getString(MainActivity.KEY_TARGET_LANG, "ar");

        // WakeLock خفيف — يمنع الـ CPU من النوم بس مو الشاشة
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SubtitleTranslator::WakeLock"
        );
        wakeLock.acquire(60 * 60 * 1000L); // max 1 ساعة

        // استمع لإغلاق الشاشة
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        // ابدأ
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        addOverlay();
        running = true;
        mainHandler.post(this::initRecognizer);
    }

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
        mainHandler.removeCallbacksAndMessages(null);

        // حرر الـ WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // أوقف الاستماع
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }

        // احذف الـ overlay
        if (windowManager != null && subtitleView != null) {
            try { windowManager.removeView(subtitleView); } catch (Exception ignored) {}
        }

        // ألغِ receiver
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}

        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ═══════════════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "الترجمة المباشرة", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("ترجمة فيديو مباشرة");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;

        PendingIntent stopIntent = PendingIntent.getService(
            this, 0,
            new Intent(this, SubtitleService.class).setAction(ACTION_STOP),
            flags
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("الترجمة شغالة")
            .setContentText("اضغط إيقاف للإنهاء")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Overlay
    // ═══════════════════════════════════════════════════════════

    private void addOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(18);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        subtitleView.setBackgroundColor(Color.TRANSPARENT);
        subtitleView.setPadding(16, 8, 16, 8);
        subtitleView.setMaxLines(3);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM;
        params.y = 120;

        windowManager.addView(subtitleView, params);
    }

    private void showText(String text) {
        mainHandler.post(() -> {
            if (subtitleView != null) subtitleView.setText(text);
        });
    }

    // ═══════════════════════════════════════════════════════════
    // Speech Recognition
    // ═══════════════════════════════════════════════════════════

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showText("التعرف الصوتي غير متاح");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);
        startListening();
    }

    private void startListening() {
        if (!running) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        if (!running) return;
        int idx = Math.min(silenceCount, BACKOFF_MS.length - 1);
        long delay = BACKOFF_MS[idx];
        mainHandler.postDelayed(this::startListening, delay);
    }

    private final RecognitionListener listener = new RecognitionListener() {

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                silenceCount = 0; // كلام = reset backoff
                String text = matches.get(0);
                TranslationHelper.translateAsync(text, sourceLang, targetLang,
                    translated -> showText(translated));
            }
            scheduleRestart();
        }

        @Override
        public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH
             || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                silenceCount++; // سكوت = زِد الـ backoff
            } else {
                silenceCount = 0;
            }
            scheduleRestart();
        }

        // ── Unused callbacks ─────────────────────────────────
        @Override public void onPartialResults(Bundle b) {}
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onEndOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEvent(int t, Bundle b) {}
    };
}
