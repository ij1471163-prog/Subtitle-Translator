package com.leno.subtitletranslator;
import android.util.Log;
import com.google.mlkit.nl.translate.*;
import java.util.HashMap;
import java.util.Map;

public class MLKitTranslator {
    private static final String TAG = "MLKitTranslator";
    private static final Map<String, Translator> cache = new HashMap<>();

    public interface Callback { void onResult(String text); }

    public static void translate(String text, String sourceLang, String targetLang, Callback cb) {
        String key = sourceLang + "_" + targetLang;

        Translator translator = cache.get(key);
        if (translator == null) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(toMLKitLang(sourceLang))
                .setTargetLanguage(toMLKitLang(targetLang))
                .build();
            translator = Translation.getClient(options);
            cache.put(key, translator);
        }

        final Translator t = translator;
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        t.downloadModelIfNeeded(conditions)
            .addOnSuccessListener(v -> {
                t.translate(text)
                    .addOnSuccessListener(result -> {
                        Log.d(TAG, "✅ " + result);
                        cb.onResult(result);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "translate error: " + e.getMessage());
                        cb.onResult(text);
                    });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "download error: " + e.getMessage());
                cb.onResult(text);
            });
    }

    private static String toMLKitLang(String code) {
        if (code == null) return TranslateLanguage.ENGLISH;
        String c = code.contains("-") ? code.split("-")[0].toLowerCase() : code.toLowerCase();
        switch (c) {
            case "ar": return TranslateLanguage.ARABIC;
            case "en": return TranslateLanguage.ENGLISH;
            case "tr": return TranslateLanguage.TURKISH;
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            case "fr": return TranslateLanguage.FRENCH;
            case "es": return TranslateLanguage.SPANISH;
            case "de": return TranslateLanguage.GERMAN;
            case "ru": return TranslateLanguage.RUSSIAN;
            case "zh": return TranslateLanguage.CHINESE;
            default:   return TranslateLanguage.ENGLISH;
        }
    }

    public static void cleanup() {
        for (Translator t : cache.values()) t.close();
        cache.clear();
    }
}
