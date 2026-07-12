package com.leno.subtitletranslator;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import java.io.File;

public class SecurityManager {

    // ── Root Detection ───────────────────────────────────────
    public static boolean isRooted() {
        // تحقق 1: ملفات su
        String[] paths = {
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/sd/xbin/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }

        // تحقق 2: Build tags
        String tags = Build.TAGS;
        if (tags != null && tags.contains("test-keys")) return true;

        // تحقق 3: su command
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            return p.exitValue() == 0;
        } catch (Exception ignored) {}

        return false;
    }

    // ── Debugger Detection ───────────────────────────────────
    public static boolean isDebugging() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    // ── Emulator Detection ───────────────────────────────────
    public static boolean isEmulator() {
        return Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("unknown")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.BRAND.startsWith("generic")
            || Build.DEVICE.startsWith("generic")
            || "google_sdk".equals(Build.PRODUCT);
    }

    // ── الفحص الشامل ─────────────────────────────────────────
    public static boolean isSafe(Context ctx) {
        if (BuildConfig.DEBUG) return true; // debug mode = skip security
        if (isRooted())    return false;
        if (isDebugging()) return false;
        if (isEmulator())  return false;
        return true;
    }
}
