package com.leno.subtitletranslator;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * SpeechRecognizerEngine — محرك مؤقت للتطوير.
 *
 * يستخدم Android SpeechRecognizer (Google).
 * يمكن استبداله لاحقاً بـ DeepgramEngine أو غيره
 * بدون تعديل أي كود آخر في المشروع.
 *
 * العيوب المعروفة (لذلك هو مؤقت):
 * - يعتمد على Google Services
 * - ليس ثابتاً على كل الأجهزة
 * - يحتاج إنترنت للغات غير محمّلة
 */
public class SpeechRecognizerEngine implements SpeechEngine {

    private static final String TAG = "SpeechRecognizerEngine";

    // Backoff ذكي: 0 → 1s → 2s → 5s → 15s → 30s → 60s
    private static final long[] BACKOFF = {0, 1000, 2000, 5000, 15000, 30000, 60000};

    private final Context        context;
    private SpeechRecognizer     recognizer;
    private SpeechConfig         config;
    private SpeechListener       listener;
    private final Handler        handler = new Handler(Looper.getMainLooper());
    private boolean              running  = false;
    private boolean              busy     = false;
    private int                  silence  = 0;

    public SpeechRecognizerEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void initialize(SpeechConfig config, SpeechListener listener) {
        this.config   = config;
        this.listener = listener;

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(-1, "SpeechRecognizer غير متاح على هذا الجهاز");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(internalListener);
        running = true;
        listener.onReady();
        Log.d(TAG, "✅ Initialized: " + config.sourceLanguage);
    }

    @Override
    public void startListening() {
        if (!running || busy || recognizer == null) return;
        busy = true;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.sourceLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        // لا PARTIAL_RESULTS افتراضياً = أقل CPU
        if (config.partialResults) {
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        }

        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            busy = false;
            scheduleNext();
        }
    }

    @Override
    public void stopListening() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void destroy() {
        running = false;
        busy    = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        Log.d(TAG, "Destroyed");
    }

    @Override
    public boolean isReady() {
        return recognizer != null && running;
    }

    @Override
    public String getEngineName() {
        return "SpeechRecognizerEngine (Temp)";
    }

    // ── Scheduling ───────────────────────────────────────────────

    private void scheduleNext() {
        if (!running) return;
        int idx = Math.min(silence, BACKOFF.length - 1);
        handler.postDelayed(this::startListening, BACKOFF[idx]);
    }

    private void resetRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(internalListener);
        busy = false;
        scheduleNext();
    }

    // ── Internal Listener ────────────────────────────────────────

    private final RecognitionListener internalListener = new RecognitionListener() {

        @Override
        public void onResults(Bundle results) {
            busy = false;
            ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty() && !matches.get(0).trim().isEmpty()) {
                silence = 0;
                listener.onSpeechResult(matches.get(0));
            } else {
                silence = Math.min(silence + 1, BACKOFF.length - 1);
            }
            scheduleNext();
        }

        @Override
        public void onPartialResults(Bundle results) {
            if (!config.partialResults) return;
            ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                listener.onPartialResult(matches.get(0));
            }
        }

        @Override
        public void onError(int error) {
            busy = false;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    silence = Math.min(silence + 1, BACKOFF.length - 1);
                    scheduleNext();
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    handler.postDelayed(() -> resetRecognizer(), 2000);
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    listener.onError(error, "صلاحية الميكروفون مرفوضة");
                    break;
                default:
                    silence = 0;
                    scheduleNext();
                    break;
            }
        }

        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() { silence = 0; }
        @Override public void onEndOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEvent(int t, Bundle b) {}
    };
}
