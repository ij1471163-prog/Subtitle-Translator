package com.leno.subtitletranslator.speech;

/**
 * SpeechEngine — Interface رئيسي لمحرك التعرف على الكلام.
 *
 * لتغيير المحرك لاحقاً: فقط أنشئ كلاس جديد يطبّق هذا الـ Interface
 * وغيّر سطراً واحداً في SubtitleService.
 *
 * محركات مخطط لها:
 * - SpeechRecognizerEngine (الحالي - مؤقت)
 * - DeepgramEngine (مستقبلاً)
 * - WhisperEngine (مستقبلاً إذا تحسنت الأجهزة)
 * - GoogleCloudEngine (مستقبلاً)
 */
public interface SpeechEngine {

    /**
     * تهيئة المحرك — يُستدعى مرة واحدة عند البدء.
     * @param config إعدادات اللغة والجودة
     * @param listener للاستماع لنتائج التعرف
     */
    void initialize(SpeechConfig config, SpeechListener listener);

    /**
     * ابدأ الاستماع — يستقبل صوتاً من الميكروفون أو AudioPlaybackCapture.
     */
    void startListening();

    /**
     * أوقف الاستماع مؤقتاً (الشاشة أُغلقت مثلاً).
     */
    void stopListening();

    /**
     * حرر كل الموارد — يُستدعى عند إغلاق الخدمة.
     */
    void destroy();

    /**
     * هل المحرك جاهز للعمل؟
     */
    boolean isReady();

    /**
     * اسم المحرك للـ logging والـ debugging.
     */
    String getEngineName();
}
