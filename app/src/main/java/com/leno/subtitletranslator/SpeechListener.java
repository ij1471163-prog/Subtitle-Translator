package com.leno.subtitletranslator;

/**
 * SpeechListener — Callback لاستقبال نتائج التعرف على الكلام.
 * SubtitleService يطبّق هذا الـ Interface ويستقبل النتائج.
 */
public interface SpeechListener {

    /**
     * جملة كاملة تم التعرف عليها — أرسلها للترجمة.
     * @param text النص المتعرَّف عليه
     */
    void onSpeechResult(String text);

    /**
     * نتيجة جزئية أثناء الكلام (اختياري).
     * @param partialText النص الجزئي
     */
    void onPartialResult(String partialText);

    /**
     * خطأ في المحرك.
     * @param errorCode كود الخطأ
     * @param message رسالة الخطأ
     */
    void onError(int errorCode, String message);

    /**
     * المحرك جاهز للاستماع.
     */
    void onReady();
}
