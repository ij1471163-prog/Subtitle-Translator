package com.leno.subtitletranslator.speech;

/**
 * SpeechConfig — إعدادات محرك التعرف على الكلام.
 * يُمرَّر لأي محرك عند التهيئة.
 */
public class SpeechConfig {

    public final String sourceLanguage;   // لغة المصدر (en-US, ja-JP, إلخ)
    public final int sampleRate;          // معدل العينة (16000 افتراضي)
    public final boolean partialResults;  // نتائج جزئية أثناء الكلام؟

    private SpeechConfig(Builder b) {
        this.sourceLanguage  = b.sourceLanguage;
        this.sampleRate      = b.sampleRate;
        this.partialResults  = b.partialResults;
    }

    public static class Builder {
        private String sourceLanguage = "en-US";
        private int    sampleRate     = 16000;
        private boolean partialResults = false;

        public Builder language(String lang) { this.sourceLanguage = lang; return this; }
        public Builder sampleRate(int rate)  { this.sampleRate = rate; return this; }
        public Builder partialResults(boolean v) { this.partialResults = v; return this; }
        public SpeechConfig build() { return new SpeechConfig(this); }
    }
}
