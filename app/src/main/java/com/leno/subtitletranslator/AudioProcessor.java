package com.leno.subtitletranslator;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.Locale;

/**
 * AudioProcessor — تحسين طبقة الصوت قبل إرسالها لـ Deepgram.
 * Pipeline: High-Pass Filter → Adaptive VAD (min-tracking, من الفريم الأول) → Normalization → Compressor → Limiter
 */
public class AudioProcessor {
    private static final String TAG = "AudioProcessor";

    private AutomaticGainControl agc;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    private static final int SAMPLE_RATE = 16000;

    // ===== High-Pass Filter (80Hz) =====
    private static final double HPF_CUTOFF_HZ = 80.0;
    private static final double HPF_ALPHA;
    static {
        double rc = 1.0 / (2 * Math.PI * HPF_CUTOFF_HZ);
        double dt = 1.0 / SAMPLE_RATE;
        HPF_ALPHA = rc / (rc + dt);
    }
    private double hpfPrevIn  = 0.0;
    private double hpfPrevOut = 0.0;

    // ===== Adaptive VAD — Minimum-Statistics Noise Tracking (من الفريم الأول، بلا انتظار) =====
    private static final long   CALIBRATION_SAMPLES  = SAMPLE_RATE * 2; // فقط لتحديد وقت طباعة "calibrated" بالـ log
    private static final double NOISE_MULTIPLIER      = 2.5;
    private static final double MIN_VAD_THRESHOLD     = 150;
    private static final double MAX_VAD_THRESHOLD     = 2500;
    private static final double NOISE_FALL_ALPHA      = 0.5;   // نزول سريع عند السكوت
    private static final double NOISE_RISE_ALPHA      = 0.001; // صعود بطيء جداً — ما يتلوث بكلام مستمر
    private static final int    VAD_HANGOVER_FRAMES   = 6;

    private double  noiseFloor      = -1;
    private boolean calibratedLogged = false;
    private long    calibSamples    = 0;
    private double  vadThreshold    = MIN_VAD_THRESHOLD;
    private int     hangoverCounter = 0;

    // ===== Normalization =====
    private static final int    TARGET_PEAK = (int) (Short.MAX_VALUE * 0.7);
    private static final double MAX_GAIN    = 4.0;

    // ===== Compressor/Limiter =====
    private static final double COMP_THRESHOLD  = Short.MAX_VALUE * 0.55;
    private static final double COMP_RATIO      = 3.0;
    private static final double LIMITER_CEILING = Short.MAX_VALUE * 0.95;
    private static final double ATTACK_MS       = 5.0;
    private static final double RELEASE_MS      = 50.0;
    private static final double ATTACK_COEFF    = Math.exp(-1.0 / (SAMPLE_RATE * (ATTACK_MS  / 1000.0)));
    private static final double RELEASE_COEFF   = Math.exp(-1.0 / (SAMPLE_RATE * (RELEASE_MS / 1000.0)));
    private double envelope = 0.0;

    // ===== Diagnostic Logging (DEBUG فقط) =====
    private static final int LOG_INTERVAL_FRAMES = 10;
    private long frameCount = 0;

    // ===== Platform Effects (مايك فقط) =====

    public void attachEffects(int audioSessionId) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSessionId);
                if (agc != null) agc.setEnabled(true);
                Log.d(TAG, "AGC enabled=" + (agc != null && agc.getEnabled()));
            } else Log.w(TAG, "AGC غير مدعوم على هذا الجهاز");
        } catch (Exception e) { Log.w(TAG, "AGC init failed: " + e.getMessage()); }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSessionId);
                if (aec != null) aec.setEnabled(true);
                Log.d(TAG, "AEC enabled=" + (aec != null && aec.getEnabled()));
            } else Log.w(TAG, "AEC غير مدعوم على هذا الجهاز");
        } catch (Exception e) { Log.w(TAG, "AEC init failed: " + e.getMessage()); }

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSessionId);
                if (ns != null) ns.setEnabled(true);
                Log.d(TAG, "NS enabled=" + (ns != null && ns.getEnabled()));
            } else Log.w(TAG, "NS غير مدعوم على هذا الجهاز");
        } catch (Exception e) { Log.w(TAG, "NS init failed: " + e.getMessage()); }
    }

    public void releaseEffects() {
        try { if (agc != null) { agc.release(); agc = null; } } catch (Exception ignored) {}
        try { if (aec != null) { aec.release(); aec = null; } } catch (Exception ignored) {}
        try { if (ns  != null) { ns.release();  ns  = null; } } catch (Exception ignored) {}
    }

    // ===== المسار الرئيسي =====

    public boolean normalizeAndDetectVoice(short[] buffer, int len) {
        if (buffer == null || len <= 0) return false;
        frameCount++;

        highPassFilter(buffer, len);

        int peak = 0;
        long sumSquares = 0;
        for (int i = 0; i < len; i++) {
            int v = Math.abs(buffer[i]);
            if (v > peak) peak = v;
            sumSquares += (long) buffer[i] * buffer[i];
        }
        double rms = Math.sqrt(sumSquares / (double) len);

        boolean voiceRaw = adaptiveVadDecision(rms, len);
        boolean voiceNow = voiceRaw;
        if (voiceRaw) {
            hangoverCounter = VAD_HANGOVER_FRAMES;
        } else if (hangoverCounter > 0) {
            hangoverCounter--;
            voiceNow = true;
        }

        double normGain = 1.0;
        double compGain = 1.0;

        if (voiceNow) {
            if (peak > 0 && peak < TARGET_PEAK) {
                normGain = Math.min(TARGET_PEAK / (double) peak, MAX_GAIN);
                for (int i = 0; i < len; i++) {
                    int amplified = (int) (buffer[i] * normGain);
                    if (amplified > Short.MAX_VALUE) amplified = Short.MAX_VALUE;
                    if (amplified < Short.MIN_VALUE) amplified = Short.MIN_VALUE;
                    buffer[i] = (short) amplified;
                }
            }
            compGain = compressAndLimit(buffer, len);
        }

        logDiagnostics(rms, peak, voiceNow, normGain, compGain);

        return voiceNow;
    }

    private void highPassFilter(short[] buffer, int len) {
        for (int i = 0; i < len; i++) {
            double x = buffer[i];
            double y = HPF_ALPHA * (hpfPrevOut + x - hpfPrevIn);
            hpfPrevIn  = x;
            hpfPrevOut = y;
            if (y > Short.MAX_VALUE) y = Short.MAX_VALUE;
            if (y < Short.MIN_VALUE) y = Short.MIN_VALUE;
            buffer[i] = (short) y;
        }
    }

    /** Adaptive منذ الفريم الأول — بدون انتظار فترة معايرة ثابتة العتبة. */
    private boolean adaptiveVadDecision(double rms, int len) {
        if (noiseFloor < 0) {
            noiseFloor = rms;
        } else if (rms < noiseFloor) {
            noiseFloor = noiseFloor * (1 - NOISE_FALL_ALPHA) + rms * NOISE_FALL_ALPHA;
        } else {
            noiseFloor = noiseFloor * (1 - NOISE_RISE_ALPHA) + rms * NOISE_RISE_ALPHA;
        }

        vadThreshold = clamp(noiseFloor * NOISE_MULTIPLIER, MIN_VAD_THRESHOLD, MAX_VAD_THRESHOLD);

        if (!calibratedLogged) {
            calibSamples += len;
            if (calibSamples >= CALIBRATION_SAMPLES && BuildConfig.DEBUG) {
                calibratedLogged = true;
                Log.d(TAG, "VAD stabilized: noiseFloor=" + (int) noiseFloor + " threshold=" + (int) vadThreshold);
            }
        }

        return rms > vadThreshold;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double compressAndLimit(short[] buffer, int len) {
        double gainSum = 0;
        for (int i = 0; i < len; i++) {
            double x = buffer[i];
            double absX = Math.abs(x);

            double coeff = absX > envelope ? ATTACK_COEFF : RELEASE_COEFF;
            envelope = coeff * envelope + (1 - coeff) * absX;

            double gain = 1.0;
            if (envelope > COMP_THRESHOLD) {
                double over = envelope / COMP_THRESHOLD;
                double compressedOver = Math.pow(over, 1.0 / COMP_RATIO);
                gain = (COMP_THRESHOLD * compressedOver) / envelope;
            }
            gainSum += gain;

            double y = x * gain;
            if (y > LIMITER_CEILING)  y = LIMITER_CEILING;
            if (y < -LIMITER_CEILING) y = -LIMITER_CEILING;

            buffer[i] = (short) y;
        }
        return gainSum / len;
    }

    private void logDiagnostics(double rms, int peak, boolean vad, double normGain, double compGain) {
        if (!BuildConfig.DEBUG) return; // صفر تكلفة بالإنتاج
        if (frameCount % LOG_INTERVAL_FRAMES != 0) return;
        Log.d(TAG, String.format(Locale.US,
            "frame#%d rms=%.0f peak=%d noiseFloor=%.0f threshold=%.0f vad=%s normGain=%.2f compGain=%.2f",
            frameCount, rms, peak, noiseFloor, vadThreshold, vad, normGain, compGain));
    }
}
