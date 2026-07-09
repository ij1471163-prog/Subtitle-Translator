package com.leno.subtitletranslator;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * محرك ترجمة:
 * ✓ بدون Buffer داخلي — التجميع مسؤولية SubtitleService فقط (منع التجميع المزدوج)
 * ✓ Fallback ثنائي — MyMemory أولاً + LibreTranslate كبديل
 * ✓ Retry Logic — إعادة محاولة تلقائية مع backoff
 * ✓ Cache LRU — آخر 500 ترجمة (ناجحة فقط)
 * ✓ Rate Limit Handling — كشف انقطاع الخدمة تلقائي
 * ✓ Thread-safe — آمن للاستخدام من عدة threads
 */
public class TranslationHelper {

    private static final String TAG = "TranslationHelper";

    // ===================== Configuration =====================
    private static final int CACHE_SIZE = 500;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_RETRIES = 2;
    private static final int INITIAL_BACKOFF_MS = 100;

    // ===================== Cache =====================
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    // Rate limiting detection
    private static final Map<String, Long> rateLimitTracker = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_COOLDOWN_MS = 60000; // 1 minute cooldown


    public static void translateAsync(String text, String sourceLangCode, String targetLangCode, TranslationCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onResult("");
            return;
        }
        // يترجم مباشرة بدون أي buffer إضافي — التجميع مسؤولية الطرف المستدعي (SubtitleService)
        final String toTranslate = text.trim();
        new Thread(() -> {
            String result = translate(toTranslate, sourceLangCode, targetLangCode);
            callback.onResult(result);
        }).start();
    }

    public static String translate(String text, String sourceLangCode, String targetLangCode) {
        if (text == null || text.trim().isEmpty()) return "";

        String sourceShort = sourceLangCode.split("-")[0];
        String cacheKey = sourceShort + "|" + targetLangCode + "|" + text.trim().toLowerCase();

        // 1️⃣ تحقق من الـ Cache أولاً
        synchronized (cache) {
            if (cache.containsKey(cacheKey)) {
                return cache.get(cacheKey);
            }
        }

        // 2️⃣ حاول الترجمة مع retry logic
        String result = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                result = tryTranslateViaMyMemory(text, sourceShort, targetLangCode);
                if (result != null && !result.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "MyMemory attempt " + (attempt + 1) + " failed");
                if (attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 3️⃣ Fallback: جرّب LibreTranslate لو MyMemory فشل
        if (result == null || result.isEmpty()) {
            try {
                result = tryTranslateViaLibreTranslate(text, sourceShort, targetLangCode);
                if (result != null && !result.isEmpty()) {
                    Log.d(TAG, "LibreTranslate fallback succeeded");
                }
            } catch (Exception e) {
                Log.w(TAG, "LibreTranslate fallback failed: " + e.getMessage());
            }
        }

        // 4️⃣ فشل كل المزودين: لا تُرجع النص الأصلي إطلاقًا، ولا تُخزّن أي شيء بالـ Cache.
        if (result == null || result.isEmpty()) {
            Log.w(TAG, "All translation services failed, returning empty result");
            return "";
        }

        // 5️⃣ احفظ في الـ Cache — فقط للترجمات الناجحة (غير فارغة)
        synchronized (cache) {
            cache.put(cacheKey, result);
        }

        return result;
    }

    // ===================== MyMemory (الخدمة الأساسية) =====================
    private static String tryTranslateViaMyMemory(String text, String sourceShort, String targetLangCode) throws Exception {
        String encoded = URLEncoder.encode(text, "UTF-8");
        String urlStr = "https://api.mymemory.translated.net/get?q=" + encoded
                + "&langpair=" + sourceShort + "|" + targetLangCode;

        String rateLimitKey = "mymemory";
        if (isRateLimited(rateLimitKey)) {
            throw new Exception("Rate limit cooldown active");
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "SubtitleTranslator/1.0");

        int responseCode = conn.getResponseCode();

        if (responseCode == 429) {
            markRateLimited(rateLimitKey);
            throw new Exception("Rate limit hit (429)");
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("HTTP " + responseCode);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        JSONObject json = new JSONObject(sb.toString());
        String translatedText = json.getJSONObject("responseData").getString("translatedText");
        return translatedText.isEmpty() ? null : translatedText;
    }

    // ===================== LibreTranslate Fallback =====================
    private static String tryTranslateViaLibreTranslate(String text, String sourceShort, String targetLangCode) throws Exception {
        String rateLimitKey = "libretranslate";
        if (isRateLimited(rateLimitKey)) {
            throw new Exception("Rate limit cooldown active");
        }

        String encoded = URLEncoder.encode(text, "UTF-8");
        String urlStr = "https://libretranslate.de/translate";

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "SubtitleTranslator/1.0");
        conn.setDoOutput(true);

        String body = "q=" + encoded + "&source=" + sourceShort + "&target=" + targetLangCode;
        conn.getOutputStream().write(body.getBytes("UTF-8"));

        int responseCode = conn.getResponseCode();
        if (responseCode == 429) {
            markRateLimited(rateLimitKey);
            throw new Exception("Rate limit hit (429)");
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("HTTP " + responseCode);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        JSONObject json = new JSONObject(sb.toString());
        String translatedText = json.getString("translatedText");
        return translatedText.isEmpty() ? null : translatedText;
    }

    // ===================== Rate Limit Detection =====================
    private static boolean isRateLimited(String service) {
        Long cooldownUntil = rateLimitTracker.get(service);
        if (cooldownUntil == null) return false;
        return System.currentTimeMillis() < cooldownUntil;
    }

    private static void markRateLimited(String service) {
        rateLimitTracker.put(service, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
        Log.w(TAG, "Rate-limited: " + service);
    }

    // ===================== Callback Interface =====================
    public interface TranslationCallback {
        void onResult(String translatedText);
    }

    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
