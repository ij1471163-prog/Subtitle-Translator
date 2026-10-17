package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

/**
 * KeyManager - يجيب مفاتيح API من Firebase Remote Config.
 *
 * الفائدة:
 * - المفاتيح محفوظة في Firebase (ما في الكود)
 * - لو انكشف مفتاح = تغيّره في Firebase فوراً بدون تحديث تطبيق
 * - لو تبي توقف المجانيين = تحط قيمة فاضية في Firebase
 *
 * كيف تضيف مفاتيح في Firebase Console:
 * Remote Config → Add parameter → اكتب الاسم والقيمة → Publish
 *
 * أسماء المفاتيح المطلوبة في Firebase:
 *   deepgram_key_plus  → مفتاح Deepgram للمجانيين
 *   deepgram_key_pro   → مفتاح Deepgram للـ PRO
 *   gladia_key         → مفتاح Gladia
 *   proxy_secret       → سر الـ proxy server
 */
public class KeyManager {

    private static final String TAG = "KeyManager";
    private static final String PKG = "com.leno.subtitletranslator";

    // أسماء المفاتيح في Firebase Remote Config
    private static final String RC_DEEPGRAM_PLUS = "deepgram_key_plus";
    private static final String RC_DEEPGRAM_PRO  = "deepgram_key_pro";
    private static final String RC_GLADIA        = "gladia_key";
    private static final String RC_PROXY_SECRET  = "proxy_secret";

    // Cache محلي - يُحفظ بعد أول جلب ناجح من Firebase
    private static final String PREFS_KEYS = "rc_keys_cache";

    private static FirebaseRemoteConfig remoteConfig;

    // ── تهيئة Firebase Remote Config ────────────────────────────
    // يُستدعى مرة واحدة عند بدء التطبيق من MainActivity

    public static void init(Context context) {
        remoteConfig = FirebaseRemoteConfig.getInstance();

        FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                // في Production: 3600 ثانية (ساعة) - الحد الأدنى المسموح
                // في Debug: 0 للتطوير السريع
                .setMinimumFetchIntervalInSeconds(3600)
                .build();

        remoteConfig.setConfigSettingsAsync(settings);

        // قيم افتراضية فارغة - لو Firebase غير متاح
        java.util.Map<String, Object> defaults = new java.util.HashMap<>();
        defaults.put(RC_DEEPGRAM_PLUS, "");
        defaults.put(RC_DEEPGRAM_PRO,  "");
        defaults.put(RC_GLADIA,        "");
        defaults.put(RC_PROXY_SECRET,  "");
        remoteConfig.setDefaultsAsync(defaults);

        // جلب وتفعيل القيم من Firebase
        remoteConfig.fetchAndActivate()
                .addOnSuccessListener(updated -> {
                    Log.d(TAG, "✅ Remote Config fetched, updated=" + updated);
                    // احفظ نسخة محلية كـ cache للاستخدام offline
                    cacheKeys(context);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Remote Config fetch failed, using cache: " + e.getMessage());
                    // استخدم الـ cache المحلي لو Firebase ما اشتغل
                });
    }

    // ── جلب المفاتيح ─────────────────────────────────────────────

    public static String getDeepgramPlusKey(Context ctx) {
        return getKey(ctx, RC_DEEPGRAM_PLUS);
    }

    public static String getDeepgramKey(Context ctx) {
        return getKey(ctx, RC_DEEPGRAM_PRO);
    }

    public static String getGladiaKey(Context ctx) {
        return getKey(ctx, RC_GLADIA);
    }

    public static String getProxySecret(Context ctx) {
        return getKey(ctx, RC_PROXY_SECRET);
    }

    // Gemini غير مستخدم حالياً - يرجع فاضي
    public static String getGeminiKey(Context ctx) {
        return "";
    }

    // ── منطق الجلب: Firebase أولاً ثم Cache ─────────────────────

    private static String getKey(Context ctx, String keyName) {
        // تحقق أن التطبيق أصلي
        if (!ctx.getPackageName().equals(PKG)) return "";

        // حاول من Firebase Remote Config أولاً
        if (remoteConfig != null) {
            String value = remoteConfig.getString(keyName);
            if (value != null && !value.isEmpty()) {
                return value.trim();
            }
        }

        // fallback: الـ cache المحلي لو Firebase ما رد
        return getCachedKey(ctx, keyName);
    }

    // ── Cache المحلي للـ offline ─────────────────────────────────

    private static void cacheKeys(Context ctx) {
        if (remoteConfig == null) return;
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_KEYS, Context.MODE_PRIVATE).edit();
        editor.putString(RC_DEEPGRAM_PLUS, remoteConfig.getString(RC_DEEPGRAM_PLUS));
        editor.putString(RC_DEEPGRAM_PRO,  remoteConfig.getString(RC_DEEPGRAM_PRO));
        editor.putString(RC_GLADIA,        remoteConfig.getString(RC_GLADIA));
        editor.putString(RC_PROXY_SECRET,  remoteConfig.getString(RC_PROXY_SECRET));
        editor.apply();
        Log.d(TAG, "Keys cached locally");
    }

    private static String getCachedKey(Context ctx, String keyName) {
        String value = ctx.getSharedPreferences(PREFS_KEYS, Context.MODE_PRIVATE)
                .getString(keyName, "");
        return value != null ? value.trim() : "";
    }
}
