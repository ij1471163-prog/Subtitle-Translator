package com.leno.subtitletranslator;

import android.util.Log;

/**
 * SmartSleepManager — نظام توفير طاقة تدريجي (Adaptive Frame Decimation).
 *
 * بدل Thread.sleep() داخل حلقة القراءة (خطر فقدان بيانات AudioRecord لو تأخرنا
 * عن تفريغ بفره الداخلي)، هذا التصميم يخلي read() يشتغل دائماً بمعدله الطبيعي
 * بدون توقف — صفر فقدان بيانات. التوفير الفعلي يجي من تخطي المعالجة الثقيلة
 * (HPF + VAD الكامل + Normalization + Compressor) لمعظم البفرات وقت السكوت
 * المؤكد، ومعالجة بس نسبة منها (Decimation).
 *
 * Hysteresis: ما يُعتبر الصوت "مؤكد" (رجوع لـ ACTIVE) إلا بعد إطارين متتاليين
 * فيهم صوت — يمنع التذبذب بسبب ضجيج خاطف قصير.
 *
 * الاستخدام بكل دورة قراءة:
 *   if (sleepManager.shouldProcess()) {
 *       boolean voice = processor.normalizeAndDetectVoice(buf, len);
 *       sleepManager.reportFrame(voice);
 *       if (voice) deepgram.sendAudio(buf, len);
 *   } else {
 *       sleepManager.reportSkipped();
 *   }
 */
public class SmartSleepManager {

    private static final String TAG = "SmartSleepManager";

    // ===== حدود المستويات (بالميلي ثانية) — عدّلها بسهولة من هنا =====
    private static final long LIGHT_SLEEP_AFTER_MS = 5_000;
    private static final long DEEP_SLEEP_AFTER_MS  = 30_000;
    private static final long HIBERNATE_AFTER_MS   = 120_000;

    // ===== نسبة البفرات اللي تُعالج فعلياً بكل مستوى (1 من كل N) =====
    private static final int ACTIVE_EVERY_N      = 1;  // كل بفر
    private static final int LIGHT_SLEEP_EVERY_N = 3;  // بفر من كل 3
    private static final int DEEP_SLEEP_EVERY_N  = 8;  // بفر من كل 8
    private static final int HIBERNATE_EVERY_N   = 20; // بفر من كل 20

    // ===== Hysteresis: عدد الإطارات المتتالية اللي تحتاج صوت مؤكد قبل الاستيقاظ =====
    private static final int VOICE_CONFIRM_FRAMES = 2;

    public enum Tier { ACTIVE, LIGHT_SLEEP, DEEP_SLEEP, HIBERNATE }

    private long lastConfirmedVoiceAt = System.currentTimeMillis();
    private Tier currentTier = Tier.ACTIVE;
    private int frameCounter = 0;
    private int consecutiveVoiceFrames = 0;

    /** استدعها أول كل دورة لتقرر هل هذا البفر يستاهل معالجة كاملة أو يُتخطى. */
    public boolean shouldProcess() {
        frameCounter++;
        int everyN = decimationForTier(currentTier);
        return (frameCounter % everyN) == 0;
    }

    /** استدعها بعد كل بفر تمت معالجته فعلياً (نتيجة VAD حقيقية). */
    public void reportFrame(boolean voiceDetected) {
        long now = System.currentTimeMillis();
        consecutiveVoiceFrames = voiceDetected ? consecutiveVoiceFrames + 1 : 0;

        if (consecutiveVoiceFrames >= VOICE_CONFIRM_FRAMES) {
            if (currentTier != Tier.ACTIVE) {
                Log.d(TAG, "🔆 استيقظ من " + currentTier + " → ACTIVE");
            }
            lastConfirmedVoiceAt = now;
            currentTier = Tier.ACTIVE;
            return;
        }
        updateTierFromIdle(now);
    }

    /** استدعها لكل بفر تم تخطيه بدون معالجة — عشان حساب مدة السكوت يفضل صحيح. */
    public void reportSkipped() {
        updateTierFromIdle(System.currentTimeMillis());
    }

    private void updateTierFromIdle(long now) {
        long idleMs = now - lastConfirmedVoiceAt;
        Tier newTier;
        if (idleMs >= HIBERNATE_AFTER_MS) newTier = Tier.HIBERNATE;
        else if (idleMs >= DEEP_SLEEP_AFTER_MS) newTier = Tier.DEEP_SLEEP;
        else if (idleMs >= LIGHT_SLEEP_AFTER_MS) newTier = Tier.LIGHT_SLEEP;
        else newTier = Tier.ACTIVE;

        if (newTier != currentTier) {
            Log.d(TAG, "😴 انتقل إلى " + newTier + " (سكوت منذ " + (idleMs / 1000) + " ثانية)");
            currentTier = newTier;
        }
    }

    private int decimationForTier(Tier tier) {
        switch (tier) {
            case LIGHT_SLEEP: return LIGHT_SLEEP_EVERY_N;
            case DEEP_SLEEP:  return DEEP_SLEEP_EVERY_N;
            case HIBERNATE:   return HIBERNATE_EVERY_N;
            default:          return ACTIVE_EVERY_N;
        }
    }

    public Tier getCurrentTier() {
        return currentTier;
    }
}
