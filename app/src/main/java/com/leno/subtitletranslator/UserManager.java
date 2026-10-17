package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UserManager {

    // ── حدود الاستخدام ──────────────────────────────────────────
    private static final long FREE_LIMIT_MS  = 10  * 60 * 1000L;  // 10 دقائق يومياً
    private static final long PLUS_LIMIT_MS  = 180 * 60 * 1000L;  // 3 ساعات يومياً
    private static final long PRO_LIMIT_MS   = 480 * 60 * 1000L;  // 8 ساعات يومياً

    // ── Product IDs (نفس Google Play Console) ───────────────────
    public static final String SKU_PLUS_WEEKLY   = "plus_weekly";
    public static final String SKU_PLUS_MONTHLY  = "plus_monthly";
    public static final String SKU_PLUS_YEARLY   = "plus_yearly";
    public static final String SKU_PRO_WEEKLY    = "pro_weekly";
    public static final String SKU_PRO_MONTHLY   = "pro_monthly";
    public static final String SKU_PRO_YEARLY    = "pro_yearly";

    // ── Tiers ────────────────────────────────────────────────────
    public enum Tier { FREE, PLUS, PRO }

    private final SharedPreferences prefs;
    private long translationStartTime = 0;

    private final Context context;
    public UserManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE);
    }

    // ── Tier الحالي ──────────────────────────────────────────────
    public Tier getCurrentTier() {
        // FIX: كان الافتراضي "PLUS" (تعليق // DEBUG يؤكد إنه كود تجربة منسي) -
        // يعني كل مستخدم جديد يحصل PLUS مجاناً تلقائياً بدون اشتراك. لازم الافتراضي FREE.
        // تحقق من Device ID لمنع إعادة التثبيت
        String savedId = prefs.getString("device_id", "");
        String currentId = Settings.Secure.getString(this.context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!savedId.equals(currentId)) {
            // جهاز جديد أو تثبيت جديد - احتفظ بالتاريخ
            prefs.edit().putString("device_id", currentId).apply();
        }
        String tier = prefs.getString("tier", "FREE");
        try { return Tier.valueOf(tier); }
        catch (Exception e) { return Tier.FREE; }
    }

    public void setTier(Tier tier) {
        prefs.edit().putString("tier", tier.name()).apply();
    }

    // ── للتوافق مع الكود القديم ──────────────────────────────────
    public boolean isSubscribed() {
        return getCurrentTier() != Tier.FREE;
    }

    public boolean isPro() {
        return getCurrentTier() == Tier.PRO;
    }

    // ── هل يقدر يترجم؟ ──────────────────────────────────────────
    public boolean canTranslate() {
        resetDailyIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        return used < getDailyLimit();
    }

    // ── الحد اليومي حسب الخطة ───────────────────────────────────
    public long getDailyLimit() {
        switch (getCurrentTier()) {
            case PRO:  return PRO_LIMIT_MS;
            case PLUS: return PLUS_LIMIT_MS;
            default:   return FREE_LIMIT_MS;
        }
    }

    // ── الوقت المتبقي ────────────────────────────────────────────
    public long getRemainingTime() {
        resetDailyIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        return Math.max(0, getDailyLimit() - used);
    }

    // ── تتبع الاستخدام ───────────────────────────────────────────
    public void startTranslation() {
        translationStartTime = System.currentTimeMillis();
        // احفظ وقت البداية في SharedPreferences
        prefs.edit().putLong("session_start", translationStartTime).apply();
    }

    public void stopTranslation() {
        if (translationStartTime == 0) return;
        long duration = System.currentTimeMillis() - translationStartTime;
        long used = prefs.getLong("daily_usage_ms", 0);
        prefs.edit().putLong("daily_usage_ms", used + duration).apply();
        translationStartTime = 0;
    }

    // ── reset يومي ───────────────────────────────────────────────
    private void resetDailyIfNewDay() {
        // كل الخطط تتجدد يومياً بما فيها FREE
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String last  = prefs.getString("last_date", "");
        if (!today.equals(last)) {
            prefs.edit()
                .putLong("daily_usage_ms", 0)
                .putString("last_date", today)
                .apply();
        }
    }

    // ── مكافأة مشاهدة إعلان (+30 دقيقة) ────────────────────────
    private static final long REWARD_BONUS_MS = 30 * 60 * 1000L; // 30 دقيقة
    private static final String KEY_REWARD_COUNT = "reward_count_today";
    private static final String KEY_REWARD_DATE  = "reward_date";
    private static final int MAX_REWARDS_PER_DAY = 3; // حد أقصى 3 مكافآت يومياً = 90 دقيقة

    /** هل يقدر يحصل على مكافأة إضافية اليوم؟ */
    public boolean canEarnReward() {
        if (getCurrentTier() != Tier.FREE) return false; // PLUS/PRO ما يحتاجون
        return getRewardCountToday() < MAX_REWARDS_PER_DAY;
    }

    /** عدد المكافآت المتبقية اليوم */
    public int remainingRewardsToday() {
        return Math.max(0, MAX_REWARDS_PER_DAY - getRewardCountToday());
    }

    /** يُستدعى بعد ما يشاهد المستخدم الإعلان كامل - يضيف +30 دقيقة لرصيده */
    public void applyRewardBonus() {
        if (!canEarnReward()) return;
        resetDailyIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        // نطرح 30 دقيقة من الاستخدام (= نضيف للرصيد المتبقي)
        long newUsed = Math.max(0, used - REWARD_BONUS_MS);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        int count = getRewardCountToday() + 1;
        prefs.edit()
            .putLong("daily_usage_ms", newUsed)
            .putInt(KEY_REWARD_COUNT, count)
            .putString(KEY_REWARD_DATE, today)
            .apply();
    }

    private int getRewardCountToday() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        String saved = prefs.getString(KEY_REWARD_DATE, "");
        if (!today.equals(saved)) return 0;
        return prefs.getInt(KEY_REWARD_COUNT, 0);
    }

    // ── معلومات الخطة للعرض ─────────────────────────────────────
    public String getTierName() {
        switch (getCurrentTier()) {
            case PRO:  return "PRO";
            case PLUS: return "PLUS";
            default:   return "مجاني";
        }
    }

    public String getLimitText() {
        switch (getCurrentTier()) {
            case PRO:  return "8 ساعات/يوم";
            case PLUS: return "3 ساعات/يوم";
            default:   return "10 دقائق/يوم";
        }
    }
}
