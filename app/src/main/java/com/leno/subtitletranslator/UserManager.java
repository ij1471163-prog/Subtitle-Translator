package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UserManager {
    private static final long FREE_LIMIT_MS = 30 * 60 * 1000L; // 30 دقيقة
    private final SharedPreferences prefs;
    private long translationStartTime = 0;

    public UserManager(Context context) {
        prefs = context.getSharedPreferences("user_data", Context.MODE_PRIVATE);
    }

    public boolean isSubscribed() {
        return prefs.getBoolean("is_subscribed", false);
    }

    public boolean canTranslate() {
        if (isSubscribed()) return true;
        resetDailyUsageIfNewDay();
        return prefs.getLong("daily_usage_ms", 0) < FREE_LIMIT_MS;
    }

    public void startTranslation() {
        translationStartTime = System.currentTimeMillis();
    }

    public void stopTranslation() {
        if (isSubscribed() || translationStartTime == 0) return;
        long used = prefs.getLong("daily_usage_ms", 0);
        long duration = System.currentTimeMillis() - translationStartTime;
        prefs.edit().putLong("daily_usage_ms", used + duration).apply();
        translationStartTime = 0;
    }

    public long getRemainingTime() {
        if (isSubscribed()) return Long.MAX_VALUE;
        resetDailyUsageIfNewDay();
        long used = prefs.getLong("daily_usage_ms", 0);
        return Math.max(0, FREE_LIMIT_MS - used);
    }

    private void resetDailyUsageIfNewDay() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String lastDate = prefs.getString("last_usage_date", "");
        if (!today.equals(lastDate)) {
            prefs.edit()
                .putLong("daily_usage_ms", 0)
                .putString("last_usage_date", today)
                .apply();
        }
    }
}
