package com.leno.subtitletranslator;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

import java.util.Calendar;

/**
 * AdManager - نقطة مركزية وحيدة للإعلانات بالتطبيق.
 *
 * قواعد التصميم (عشان ما يزعج المستخدم):
 * - إعلانات لمستخدمي FREE فقط. PLUS/PRO ما يشوفون أي إعلان أبداً.
 * - Banner: مكان ثابت بالـ MainActivity، أبداً فوق الـ overlay أو أثناء الترجمة الفعلية.
 * - Interstitial: يظهر بس بنقطة توقف طبيعية (بعد إيقاف جلسة ترجمة من MainActivity)،
 *   أبداً عند فتح التطبيق أول مرة أو بمنتصف طلب صلاحية/بدء تشغيل.
 * - Cooldown 3 دقائق بين كل interstitial والثاني.
 * - أول جلستين للمستخدم الجديد بدون أي interstitial.
 */
public class AdManager {
    private static final String TAG = "AdManager";
    private static final String PREFS = "ad_manager_prefs";
    private static final String KEY_LAST_INTERSTITIAL = "last_interstitial_ts";
    private static final String KEY_SESSION_COUNT = "session_count";

    private static final long INTERSTITIAL_COOLDOWN_MS = 3 * 60 * 1000L; // 3 دقائق
    private static final int MIN_SESSIONS_BEFORE_ADS = 2; // بدون إعلان أول جلستين
    private static final int MAX_APPOPEN_PER_DAY = 1; // FIX: مرة وحدة باليوم بدل مرتين
    private static final String KEY_APPOPEN_DATE = "appopen_date";
    private static final String KEY_APPOPEN_COUNT = "appopen_count";
    // ⚠️ معرفات إعلانية حقيقية (SubtitleTranslator - AdMob) - App ID الرئيسي يروح بالـ AndroidManifest.xml
    // App ID (يُستخدم بالـ Manifest بس، مو هنا): ca-app-pub-8342669226787286~9358139906
    private static final String APPOPEN_UNIT_ID = "ca-app-pub-8342669226787286/9170437642"; // حقيقي
    private static final String REWARDED_UNIT_ID = "ca-app-pub-8342669226787286/9849094490"; // حقيقي

    // ⚠️ لسا test IDs - بدّلها بمعرفات Banner/Interstitial الحقيقية حقتك أول ما تسويها
    private static final String BANNER_UNIT_ID_TEST = "ca-app-pub-3940256099942544/9214589741";
    private static final String INTERSTITIAL_UNIT_ID_TEST = "ca-app-pub-3940256099942544/1033173712";

    private static volatile boolean initialized = false;
    private static volatile InterstitialAd interstitialAd = null;
    private static volatile boolean interstitialLoading = false;

    private final Context appContext;
    private final SharedPreferences prefs;

    public AdManager(Context context){
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** استدعِها مرة وحدة بـ MainActivity.onCreate() قبل أي استخدام ثاني للكلاس. */
    public void init(){
        if(initialized) return;
        initialized = true;
        MobileAds.initialize(appContext, status -> {
            Log.d(TAG, "MobileAds initialized");
            preloadInterstitial();
            preloadAppOpenAd();
            preloadRewarded();
        });
    }

    /** سجّل جلسة استخدام جديدة - استدعها مرة بكل onCreate لـ MainActivity. */
    public void recordSessionOpened(){
        int count = prefs.getInt(KEY_SESSION_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_SESSION_COUNT, count).apply();
    }

    private boolean isEligibleForAds(Context context){
        UserManager.Tier tier = new UserManager(context).getCurrentTier();
        if(tier != UserManager.Tier.FREE) return false; // PLUS/PRO بدون إعلانات نهائياً
        int sessions = prefs.getInt(KEY_SESSION_COUNT, 0);
        return sessions > MIN_SESSIONS_BEFORE_ADS;
    }

    // ===================== Banner =====================

    /**
     * يضيف Banner ثابت داخل الحاوية المعطاة (مثلاً LinearLayout فاضي بأسفل الشاشة).
     * ما يظهر شي لمستخدمي PLUS/PRO أو بالجلسات الأولى - الحاوية تفضل مخفية (GONE).
     */
    public void attachBanner(Activity activity, ViewGroup container){
        if(!isEligibleForAds(activity)){
            container.setVisibility(ViewGroup.GONE);
            return;
        }
        AdView bannerView = new AdView(activity);
        bannerView.setAdUnitId(BANNER_UNIT_ID_TEST); // TODO: بدّلها بمعرف Banner الحقيقي حقك
        bannerView.setAdSize(getAdaptiveBannerSize(activity));
        bannerView.setAdListener(new AdListener(){
            @Override public void onAdLoaded(){
                container.setVisibility(ViewGroup.VISIBLE);
            }
            @Override public void onAdFailedToLoad(@NonNull LoadAdError error){
                Log.w(TAG,"banner failed: "+error.getMessage());
                container.setVisibility(ViewGroup.GONE);
            }
        });
        container.removeAllViews();
        container.addView(bannerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bannerView.loadAd(new AdRequest.Builder().build());
    }

    private AdSize getAdaptiveBannerSize(Activity activity){
        DisplayMetrics outMetrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(outMetrics);
        float widthPixels = outMetrics.widthPixels;
        int adWidth = (int)(widthPixels / outMetrics.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth);
    }

    // ===================== Interstitial =====================

    private void preloadInterstitial(){
        if(interstitialLoading || interstitialAd != null) return;
        interstitialLoading = true;
        InterstitialAd.load(appContext, INTERSTITIAL_UNIT_ID_TEST, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback(){
                    @Override public void onAdLoaded(@NonNull InterstitialAd ad){
                        interstitialAd = ad;
                        interstitialLoading = false;
                        Log.d(TAG,"interstitial preloaded");
                    }
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error){
                        interstitialAd = null;
                        interstitialLoading = false;
                        Log.w(TAG,"interstitial failed: "+error.getMessage());
                    }
                });
    }

    /**
     * يعرض interstitial فقط لو: المستخدم FREE + تجاوز الجلسات الدنيا + انتهى الـ cooldown + فيه إعلان جاهز مسبقاً.
     * استدعها فقط بنقطة توقف طبيعية (مثلاً زر "إيقاف الترجمة" بالـ MainActivity) -
     * أبداً وسط طلب صلاحية أو بدء تشغيل الخدمة.
     *
     * @param onDismissedOrSkipped يُستدعى دايماً (ظهر الإعلان أو لأ) عشان تكمل الـ flow بعده بدون ما تعلق
     */
    public void maybeShowInterstitial(Activity activity, Runnable onDismissedOrSkipped){
        if(!isEligibleForAds(activity) || !isCooldownOver() || interstitialAd == null){
            preloadInterstitial();
            if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            return;
        }
        InterstitialAd ad = interstitialAd;
        interstitialAd = null; // استهلكناه - لازم نحمّل وحد جديد بعده
        ad.setFullScreenContentCallback(new FullScreenContentCallback(){
            @Override public void onAdDismissedFullScreenContent(){
                markInterstitialShown();
                preloadInterstitial();
                if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error){
                preloadInterstitial();
                if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            }
        });
        ad.show(activity);
    }

    private boolean isCooldownOver(){
        long last = prefs.getLong(KEY_LAST_INTERSTITIAL, 0);
        return System.currentTimeMillis() - last >= INTERSTITIAL_COOLDOWN_MS;
    }

    private void markInterstitialShown(){
        prefs.edit().putLong(KEY_LAST_INTERSTITIAL, System.currentTimeMillis()).apply();
    }

    // ===================== App Open =====================
    // يظهر بحد أقصى مرة وحدة باليوم - العداد يتصفّر تلقائياً كل يوم جديد بالتقويم.

    private static volatile AppOpenAd appOpenAd = null;
    private static volatile boolean appOpenLoading = false;
    private static volatile long appOpenLoadTimeMs = 0;

    private void preloadAppOpenAd(){
        if(appOpenLoading || appOpenAd != null) return;
        appOpenLoading = true;
        AppOpenAd.load(appContext, APPOPEN_UNIT_ID, new AdRequest.Builder().build(),
                new AppOpenAd.AppOpenAdLoadCallback(){
                    @Override public void onAdLoaded(@NonNull AppOpenAd ad){
                        appOpenAd = ad;
                        appOpenLoading = false;
                        appOpenLoadTimeMs = System.currentTimeMillis();
                        Log.d(TAG,"app open preloaded");
                    }
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error){
                        appOpenAd = null;
                        appOpenLoading = false;
                        Log.w(TAG,"app open failed: "+error.getMessage());
                    }
                });
    }

    // إعلانات AdMob تنتهي صلاحيتها تقريباً بعد 4 ساعات حسب توصية جوجل
    private boolean isAppOpenAdFresh(){
        return appOpenAd != null && (System.currentTimeMillis() - appOpenLoadTimeMs) < 4*60*60*1000L;
    }

    private String todayKey(){
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.YEAR)+"-"+cal.get(Calendar.DAY_OF_YEAR);
    }

    private int getAppOpenCountToday(){
        String storedDay = prefs.getString(KEY_APPOPEN_DATE, "");
        String today = todayKey();
        if(!today.equals(storedDay)){
            // يوم جديد - نصفّر العداد تلقائياً
            prefs.edit().putString(KEY_APPOPEN_DATE, today).putInt(KEY_APPOPEN_COUNT, 0).apply();
            return 0;
        }
        return prefs.getInt(KEY_APPOPEN_COUNT, 0);
    }

    private void incrementAppOpenCount(){
        int count = getAppOpenCountToday() + 1;
        prefs.edit().putString(KEY_APPOPEN_DATE, todayKey()).putInt(KEY_APPOPEN_COUNT, count).apply();
    }

    /**
     * يعرض App Open بس لو: المستخدم FREE + تجاوز الجلسات الدنيا + ما تجاوز الحد اليومي (مرة وحدة)
     * + فيه إعلان محمّل وطازج (أقل من 4 ساعات). استدعها بأول onCreate/onResume لـ MainActivity،
     * مو وسط طلب صلاحية أو أثناء الترجمة شغالة بالخلفية.
     *
     * @param onDismissedOrSkipped يُستدعى دايماً عشان تكمل فتح الشاشة الرئيسية بعده بدون تعليق
     */
    public void maybeShowAppOpenAd(Activity activity, Runnable onDismissedOrSkipped){
        if(!isEligibleForAds(activity) || getAppOpenCountToday() >= MAX_APPOPEN_PER_DAY || !isAppOpenAdFresh()){
            preloadAppOpenAd();
            if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            return;
        }
        AppOpenAd ad = appOpenAd;
        appOpenAd = null; // استهلكناه - لازم نحمّل وحد جديد بعده
        ad.setFullScreenContentCallback(new FullScreenContentCallback(){
            @Override public void onAdDismissedFullScreenContent(){
                incrementAppOpenCount();
                preloadAppOpenAd();
                if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error){
                preloadAppOpenAd();
                if(onDismissedOrSkipped != null) onDismissedOrSkipped.run();
            }
        });
        ad.show(activity);
    }

    // ===================== Rewarded =====================
    // إعلان مكافأة - بطلب المستخدم نفسه (زر "شاهد إعلان لفتح PLUS")، فمو نفس حساسية الإعلانات
    // الغير مطلوبة (Interstitial/App Open)، لذا بدون قيود جلسات أو cooldown زمني.

    private static volatile RewardedAd rewardedAd = null;
    private static volatile boolean rewardedLoading = false;

    private void preloadRewarded(){
        if(rewardedLoading || rewardedAd != null) return;
        rewardedLoading = true;
        RewardedAd.load(appContext, REWARDED_UNIT_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback(){
                    @Override public void onAdLoaded(@NonNull RewardedAd ad){
                        rewardedAd = ad;
                        rewardedLoading = false;
                        Log.d(TAG,"rewarded preloaded");
                    }
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error){
                        rewardedAd = null;
                        rewardedLoading = false;
                        Log.w(TAG,"rewarded failed: "+error.getMessage());
                    }
                });
    }

    /** يفيدك تعرض/تخفي زر "شاهد إعلان لفتح PLUS" حسب جهوزية الإعلان. */
    public boolean isRewardedReady(){
        return rewardedAd != null;
    }

    /**
     * يعرض إعلان مكافأة كامل. المستخدم لازم يكمله عشان يستحق المكافأة (سلوك طبيعي لهذا النوع).
     * AdManager ما يغيّر أي شي بحساب المستخدم بنفسه - أنت اللي تقرر شكل "فتح PLUS مؤقتاً"
     * بمنطقك الخاص (UserManager) داخل onEarned.
     *
     * @param onEarned يُستدعى فقط لو المستخدم شاف الإعلان كامل واستحق المكافأة
     * @param onSkippedOrFailed يُستدعى لو ما فيه إعلان جاهز، أو فشل العرض، أو المستخدم طلع بدون إكمال
     */
    public void showRewarded(Activity activity, Runnable onEarned, Runnable onSkippedOrFailed){
        UserManager.Tier tier = new UserManager(activity).getCurrentTier();
        if(tier != UserManager.Tier.FREE || rewardedAd == null){
            preloadRewarded();
            if(onSkippedOrFailed != null) onSkippedOrFailed.run();
            return;
        }
        RewardedAd ad = rewardedAd;
        rewardedAd = null; // استهلكناه - لازم نحمّل وحد جديد بعده
        final boolean[] earned = {false};
        ad.setFullScreenContentCallback(new FullScreenContentCallback(){
            @Override public void onAdDismissedFullScreenContent(){
                preloadRewarded();
                if(earned[0]){
                    if(onEarned != null) onEarned.run();
                } else {
                    if(onSkippedOrFailed != null) onSkippedOrFailed.run();
                }
            }
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error){
                preloadRewarded();
                if(onSkippedOrFailed != null) onSkippedOrFailed.run();
            }
        });
        ad.show(activity, rewardItem -> earned[0] = true);
    }
}
