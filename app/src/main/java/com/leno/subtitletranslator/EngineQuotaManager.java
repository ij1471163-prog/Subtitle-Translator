package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class EngineQuotaManager {

    // حدود يومية بالدقائق
    private static final long GLADIA_LIMIT_MS=600*60*1000L;
    private static final long DEEPGRAM_LIMIT_MS      = 200 * 60 * 1000L;

    public enum Engine { GLADIA, DEEPGRAM, LOCAL }

    private final SharedPreferences prefs;

    public EngineQuotaManager(Context ctx) {
        prefs = ctx.getSharedPreferences("quota", Context.MODE_PRIVATE);
        resetIfNewDay();
    }

    // ── اختار المحرك المتاح ──────────────────────────────────
    public Engine getBestEngine() {
        if (getUsed("deepgram")<DEEPGRAM_LIMIT_MS) return Engine.DEEPGRAM;
        if (getUsed("gladia")<GLADIA_LIMIT_MS) return Engine.GLADIA;
        return Engine.LOCAL;
    }

    // ── سجّل استخدام ─────────────────────────────────────────
    public void recordUsage(Engine engine, long durationMs) {
        String key = getKey(engine);
        if (key == null) return;
        long used = prefs.getLong(key, 0);
        prefs.edit().putLong(key, used + durationMs).apply();
    }

    // ── كم باقي؟ ─────────────────────────────────────────────
    public long getRemaining(Engine engine) {
        switch (engine) {
            case DEEPGRAM:     return Math.max(0, DEEPGRAM_LIMIT_MS     - getUsed("deepgram"));
            default: return Long.MAX_VALUE;
        }
    }

    private long getUsed(String key) {
        return prefs.getLong(key, 0);
    }

    private String getKey(Engine e) {
        switch (e) {
            case GLADIA: return "gladia";
            case DEEPGRAM:     return "deepgram";
            default: return null;
        }
    }

    // ── reset يومي ───────────────────────────────────────────
    private void resetIfNewDay() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String last  = prefs.getString("quota_date", "");
        if (!today.equals(last)) {
            prefs.edit()
                .putLong("gladia",0)
                .putLong("deepgram", 0)
                .putString("quota_date", today)
                .apply();
        }
    }
}
