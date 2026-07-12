# إخفاء أسماء الكلاسات والمتغيرات
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# تشفير أسماء الكلاسات
-repackageclasses ''
-allowaccessmodification

# KeyManager — أهم ملف نحميه
-keep class com.leno.subtitletranslator.KeyManager { *; }
-keepclassmembers class com.leno.subtitletranslator.KeyManager {
    private static final java.lang.String *;
}

# Android components لازم تبقى
-keep class com.leno.subtitletranslator.MainActivity { *; }
-keep class com.leno.subtitletranslator.SubtitleService { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Billing
-keep class com.android.billingclient.** { *; }

# إزالة Logs في Release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
