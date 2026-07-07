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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;


/**
 * SubtitleService — قلب التطبيق.
 *
 * لا يعرف شيئاً عن SpeechRecognizer أو Deepgram أو غيرهم.
 * يتعامل فقط مع SpeechEngine Interface.
 *
 * لتغيير المحرك: غيّر سطر createEngine() فقط.
 */
public class SubtitleService extends Service implements SpeechListener {

    private static final String CHANNEL_ID  = "subtitle_ch";
    private static final int    NOTIF_ID    = 1001;
    public  static final String ACTION_STOP = "com.leno.subtitletranslator.STOP";

    private WindowManager         wm;
    private TextView              overlay;
    private SpeechEngine          engine;
    private PowerManager.WakeLock wakeLock;
    private final Handler         handler = new Handler(Looper.getMainLooper());
    private boolean               running = false;

    private String sourceLang = "en-US";
    private String targetLang = "ar";

    // إغلاق الشاشة = إيقاف فوري
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) stopSelf();
        }
    };

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        sourceLang = prefs.getString(MainActivity.KEY_SOURCE_LANG, "en-US");
        targetLang = prefs.getString(MainActivity.KEY_TARGET_LANG, "ar");

        // WakeLock خفيف — CPU فقط
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ST::lock");
        wakeLock.acquire(60 * 60 * 1000L);

        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        createChannel();
        startForeground(NOTIF_ID, buildNotif());
        addOverlay();

        running = true;
        startEngine();
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
        handler.removeCallbacksAndMessages(null);
        if (engine != null)  engine.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ═══════════════════════════════════════════════════════════
    // Engine Management
    // تغيير المحرك: غيّر هذا الـ method فقط
    // ═══════════════════════════════════════════════════════════

    private void startEngine() {
        engine = createEngine();

        SpeechConfig config = new SpeechConfig.Builder()
            .language(sourceLang)
            .sampleRate(16000)
            .partialResults(false) // false = أقل CPU
            .build();

        engine.initialize(config, this);
    }

    /**
     * ── غيّر هذا السطر لتغيير المحرك ──
     *
     * الحالي (مؤقت):
     *   return new SpeechRecognizerEngine(this);
     *
     * مستقبلاً:
     *   return new DeepgramEngine(this, API_KEY);
     *   return new WhisperEngine(this);
     *   return new GoogleCloudEngine(this, API_KEY);
     */
    private SpeechEngine createEngine() {
        return new SpeechRecognizerEngine(this);
    }

    // ═══════════════════════════════════════════════════════════
    // SpeechListener Implementation
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onReady() {
        engine.startListening();
    }

    @Override
    public void onSpeechResult(String text) {
        if (!running || text == null || text.trim().isEmpty()) return;
        // ترجم النص وعرضه في الـ overlay
        TranslationHelper.translateAsync(text, sourceLang, targetLang,
            translated -> showOverlay(translated));
    }

    @Override
    public void onPartialResult(String partialText) {
        // اختياري: عرض النص الجزئي بلون مختلف
        // showOverlay("..." + partialText);
    }

    @Override
    public void onError(int errorCode, String message) {
        // لا نعرض الأخطاء للمستخدم — المحرك يتعامل معها داخلياً
    }

    // ═══════════════════════════════════════════════════════════
    // Overlay
    // ═══════════════════════════════════════════════════════════

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

    private void showOverlay(String text) {
        handler.post(() -> { if (overlay != null) overlay.setText(text); });
    }

    // ═══════════════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════════════

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
}
