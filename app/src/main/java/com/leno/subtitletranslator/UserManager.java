package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UserManager {

    // ── حدود الاستخدام ──────────────────────────────────────────
    private static final long FREE_LIMIT_MS  = 30  * 60 * 1000L;  // 30 دقيقة
    private static final long PLUS_LIMIT_MS  = 180 * 60 * 1000L;  // 3 ساعات
    private static final long PRO_LIMIT_MS   = 480 * 60 * 1000L;  // 8 ساعات

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

    public UserManager(Context context) {
        prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE);
    }

    // ── Tier الحالي ──────────────────────────────────────────────
    public Tier getCurrentTier() {
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
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String last  = prefs.getString("last_date", "");
        if (!today.equals(last)) {
            prefs.edit()
                .putLong("daily_usage_ms", 0)
                .putString("last_date", today)
                .apply();
        }
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
            default:   return "30 دقيقة/يوم";
        }
    }
}
