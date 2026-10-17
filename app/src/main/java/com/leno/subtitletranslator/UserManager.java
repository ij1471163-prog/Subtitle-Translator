package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UserManager {

    // ── حدود الاستخدام ──────────────────────────────────────────
    // المجاني: 30 دقيقة ثابتة - لا تزيد ولا تتجدد تلقائياً
    // الزيادة الوحيدة: عند مشاهدة إعلان Interstitial (راجع AdManager)
    private static final long FREE_LIMIT_MS  = 30  * 60 * 1000L;  // 30 دقيقة ثابتة
    private static final long PLUS_LIMIT_MS  = 180 * 60 * 1000L;  // 3 ساعات يومياً
    private static final long PRO_LIMIT_MS   = 480 * 60 * 1000L;  // 8 ساعات يومياً

    // ── Interstitial cooldown للمجاني (يومين = 48 ساعة) ─────────
    // بعد ما يشوف المجاني إعلان Interstitial، يوقف يومين ثم يرجع
    public static final long INTERSTITIAL_FREE_COOLDOWN_MS = 48 * 60 * 60 * 1000L; // 48 ساعة
    private static final String KEY_LAST_INTERSTITIAL_FREE = "last_interstitial_free_ts";

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
        String savedId = prefs.getString("device_id", "");
        String currentId = Settings.Secure.getString(this.context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!savedId.equals(currentId)) {
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
        if (getCurrentTier() != Tier.FREE) resetDailyIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        return used < getDailyLimit();
    }

    // ── الحد حسب الخطة ──────────────────────────────────────────
    public long getDailyLimit() {
        switch (getCurrentTier()) {
            case PRO:  return PRO_LIMIT_MS;
            case PLUS: return PLUS_LIMIT_MS;
            default:   return FREE_LIMIT_MS; // 30 دقيقة ثابتة للمجاني
        }
    }

    // ── الوقت المتبقي ────────────────────────────────────────────
    public long getRemainingTime() {
        if (getCurrentTier() != Tier.FREE) resetDailyIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        return Math.max(0, getDailyLimit() - used);
    }

    // ── تتبع الاستخدام الذكي ─────────────────────────────────────
    // الرصيد يُحسب فقط أثناء وجود كلام فعلي، لا خلال الصمت أو الموسيقى

    /** يبدأ الجلسة (لا يحسب رصيداً بعد - ينتظر resumeActiveUsage) */
    public void startTranslation() {
        translationStartTime = 0; // لا نبدأ العداد إلا عند كلام فعلي
        prefs.edit().putLong("active_segment_start", 0).apply();
    }

    /** يُستدعى عند وصول كلام - يبدأ حساب الرصيد */
    public void resumeActiveUsage() {
        long now = System.currentTimeMillis();
        long segStart = prefs.getLong("active_segment_start", 0);
        if (segStart == 0) {
            // ابدأ مقطع جديد
            prefs.edit().putLong("active_segment_start", now).apply();
            translationStartTime = now;
        }
    }

    /** يُستدعى عند الصمت - يوقف حساب الرصيد ويحفظ ما استهلك */
    public void pauseActiveUsage() {
        long segStart = prefs.getLong("active_segment_start", 0);
        if (segStart == 0) return;
        long duration = System.currentTimeMillis() - segStart;
        if (duration > 0) {
            long used = prefs.getLong("daily_usage_ms", 0);
            prefs.edit()
                .putLong("daily_usage_ms", used + duration)
                .putLong("active_segment_start", 0)
                .apply();
        }
        translationStartTime = 0;
    }

    /** يُستدعى عند إيقاف الخدمة - يغلق أي مقطع مفتوح */
    public void stopTranslation() {
        pauseActiveUsage(); // احفظ ما تبقى من المقطع الحالي
    }

    // ── reset يومي (للمشتركين فقط - المجاني لا يتجدد تلقائياً) ──
    private void resetDailyIfNewDay() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String last  = prefs.getString("last_date", "");
        if (!today.equals(last)) {
            prefs.edit()
                .putLong("daily_usage_ms", 0)
                .putString("last_date", today)
                .apply();
        }
    }

    // ── Interstitial للمجاني: هل انتهى الـ cooldown؟ ────────────
    // يُستخدم من AdManager لتقرير إذا يعرض Interstitial للمجاني أم لا
    public boolean isFreeInterstitialReady() {
        if (getCurrentTier() != Tier.FREE) return false;
        if (getRemainingTime() > 0) return false; // لو عنده رصيد ما يحتاج إعلان
        long last = prefs.getLong(KEY_LAST_INTERSTITIAL_FREE, 0);
        return System.currentTimeMillis() - last >= INTERSTITIAL_FREE_COOLDOWN_MS;
    }

    // يُستدعى من AdManager بعد عرض Interstitial للمجاني
    public void markFreeInterstitialShown() {
        prefs.edit().putLong(KEY_LAST_INTERSTITIAL_FREE, System.currentTimeMillis()).apply();
    }

    // ── مكافأة مشاهدة الإعلان (+15 دقيقة) ─────────────────────
    private static final long REWARD_BONUS_MS    = 15 * 60 * 1000L; // 15 دقيقة لكل إعلان
    private static final int  MAX_REWARDS_PER_DAY = 3;               // حد 3 مرات باليوم
    private static final String KEY_REWARD_DATE   = "reward_date";
    private static final String KEY_REWARD_COUNT  = "reward_count";

    /** هل المستخدم يقدر يشاهد إعلان مكافأة الآن؟ */
    public boolean canEarnReward() {
        if (getCurrentTier() != Tier.FREE) return false; // المشتركين ما يحتاجون
        return getRemainingRewardsToday() < MAX_REWARDS_PER_DAY;
    }

    /** كم مرة باقية اليوم */
    public int remainingRewardsToday() {
        return MAX_REWARDS_PER_DAY - getRemainingRewardsToday();
    }

    private int getRemainingRewardsToday() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String savedDay = prefs.getString(KEY_REWARD_DATE, "");
        if (!today.equals(savedDay)) {
            prefs.edit().putString(KEY_REWARD_DATE, today).putInt(KEY_REWARD_COUNT, 0).apply();
            return 0;
        }
        return prefs.getInt(KEY_REWARD_COUNT, 0);
    }

    /** يُستدعى بعد انتهاء الإعلان - يضيف +15 دقيقة */
    public void applyRewardBonus() {
        if (getCurrentTier() != Tier.FREE) return;
        // أضف 15 دقيقة عن طريق تقليل daily_usage_ms
        long used = prefs.getLong("daily_usage_ms", 0);
        long newUsed = Math.max(0, used - REWARD_BONUS_MS);
        // سجّل الاستخدام
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int count = getRemainingRewardsToday() + 1;
        prefs.edit()
            .putLong("daily_usage_ms", newUsed)
            .putString(KEY_REWARD_DATE, today)
            .putInt(KEY_REWARD_COUNT, count)
            .apply();
    }

    public boolean hasEarnedRewardThisWeek() { return false; } // للتوافق

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
            default:   return "30 دقيقة";
        }
    }
}
