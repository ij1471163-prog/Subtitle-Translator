package com.leno.subtitletranslator;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * BillingManager - يتحكم في الاشتراكات مع Google Play.
 *
 * المبادئ:
 * 1. التحقق من الاشتراك عند كل فتح للتطبيق (restorePurchases)
 * 2. الاتصال الدائم مع Google Play + إعادة الاتصال التلقائي
 * 3. لو الاشتراك انتهى أو أُلغي = يرجع FREE تلقائياً
 * 4. لو offline = يحتفظ بالـ Tier المحفوظ مؤقتاً (grace period)
 */
public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";

    // Grace period: لو offline، نعطيه 3 أيام قبل ما نسقطه لـ FREE
    private static final long OFFLINE_GRACE_MS = 3 * 24 * 60 * 60 * 1000L;
    private static final String KEY_LAST_VERIFIED = "billing_last_verified";

    // فترة التحقق الدوري: كل 6 ساعات وهو شغال
    private static final long VERIFY_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private final Context context;
    private final UserManager userManager;
    private BillingClient billingClient;
    private OnBillingListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    public interface OnBillingListener {
        void onPurchaseSuccess(UserManager.Tier tier);
        void onPurchaseFailed(String message);
        default void onSubscriptionExpired() {} // اشتراك انتهى
    }

    public BillingManager(Context context, UserManager userManager) {
        this.context = context.getApplicationContext();
        this.userManager = userManager;
    }

    // ── تهيئة الاتصال ────────────────────────────────────────────

    public void init(OnBillingListener listener) {
        this.listener = listener;
        connect();
    }

    private void connect() {
        if (destroyed) return;

        billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .enableAutoServiceReconnection()
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "✅ Billing connected");
                    // تحقق فوري عند الاتصال
                    restorePurchases();
                    // ابدأ التحقق الدوري
                    schedulePeriodicVerify();
                } else {
                    Log.e(TAG, "Billing setup failed: " + result.getResponseCode());
                    // offline أو فشل - طبّق grace period
                    applyGracePeriod();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected - reconnecting...");
                // حاول إعادة الاتصال بعد 5 ثواني
                if (!destroyed) {
                    handler.postDelayed(() -> {
                        if (!destroyed && billingClient != null) {
                            billingClient.startConnection(this);
                        }
                    }, 5000);
                }
            }
        });
    }

    // ── التحقق الدوري كل 6 ساعات ────────────────────────────────

    private void schedulePeriodicVerify() {
        handler.removeCallbacksAndMessages("verify");
        handler.postDelayed(() -> {
            if (!destroyed) {
                restorePurchases();
                schedulePeriodicVerify();
            }
        }, VERIFY_INTERVAL_MS);
    }

    // ── Grace Period للـ offline ──────────────────────────────────
    // لو ما قدر يتصل بـ Google Play، يعطي المشترك 3 أيام فرصة

    private void applyGracePeriod() {
        if (userManager.getCurrentTier() == UserManager.Tier.FREE) return;

        android.content.SharedPreferences prefs =
                context.getSharedPreferences("billing_prefs", Context.MODE_PRIVATE);
        long lastVerified = prefs.getLong(KEY_LAST_VERIFIED, 0);
        long elapsed = System.currentTimeMillis() - lastVerified;

        if (lastVerified > 0 && elapsed > OFFLINE_GRACE_MS) {
            // تجاوز 3 أيام بدون تحقق = يرجع FREE
            Log.w(TAG, "Grace period expired - downgrading to FREE");
            userManager.setTier(UserManager.Tier.FREE);
            if (listener != null) {
                handler.post(() -> listener.onSubscriptionExpired());
            }
        } else {
            Log.d(TAG, "Offline grace period active: " + (elapsed / 3600000) + "h elapsed");
        }
    }

    // ── شراء اشتراك ─────────────────────────────────────────────

    public void launchPurchase(Activity activity, String productId) {
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "BillingClient not ready");
            if (listener != null) listener.onPurchaseFailed("خدمة الدفع غير جاهزة، تأكد من اتصال الإنترنت");
            return;
        }

        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
        );

        billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(products).build(),
                (billingResult, queryResult) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "Product query failed: " + billingResult.getResponseCode());
                        if (listener != null) listener.onPurchaseFailed("تعذر جلب المنتج");
                        return;
                    }

                    if (queryResult == null) return;
                    List<ProductDetails> productDetailsList = queryResult.getProductDetailsList();

                    if (productDetailsList == null || productDetailsList.isEmpty()) {
                        Log.e(TAG, "Product not found: " + productId);
                        if (listener != null) listener.onPurchaseFailed("المنتج غير متوفر");
                        return;
                    }

                    ProductDetails product = productDetailsList.get(0);
                    List<ProductDetails.SubscriptionOfferDetails> offers = product.getSubscriptionOfferDetails();

                    if (offers == null || offers.isEmpty()) {
                        if (listener != null) listener.onPurchaseFailed("لا توجد عروض اشتراك متاحة");
                        return;
                    }

                    ProductDetails.SubscriptionOfferDetails selectedOffer = selectBestOffer(offers);
                    if (selectedOffer == null) {
                        if (listener != null) listener.onPurchaseFailed("لا يوجد عرض اشتراك صالح");
                        return;
                    }

                    List<BillingFlowParams.ProductDetailsParams> params = new ArrayList<>();
                    params.add(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(product)
                                    .setOfferToken(selectedOffer.getOfferToken())
                                    .build()
                    );

                    BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(params)
                            .build();

                    billingClient.launchBillingFlow(activity, flowParams);
                }
        );
    }

    // ── اختيار أفضل عرض ─────────────────────────────────────────

    private ProductDetails.SubscriptionOfferDetails selectBestOffer(
            List<ProductDetails.SubscriptionOfferDetails> offers) {
        if (offers == null || offers.isEmpty()) return null;

        // نفضل العروض التي تبدأ بفترة مجانية
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (offer == null || offer.getPricingPhases() == null) continue;
            List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
            if (phases == null || phases.isEmpty()) continue;
            if (phases.get(0).getPriceAmountMicros() == 0) return offer;
        }
        return offers.get(0);
    }

    // ── استقبال نتيجة الشراء ────────────────────────────────────

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase != null) handlePurchase(purchase);
            }
        } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (listener != null) listener.onPurchaseFailed("تم إلغاء الشراء");
        } else {
            Log.w(TAG, "Purchase failed: " + result.getResponseCode());
            if (listener != null) listener.onPurchaseFailed("فشل الشراء");
        }
    }

    // ── معالجة الشراء ───────────────────────────────────────────

    private void handlePurchase(Purchase purchase) {
        if (purchase == null) return;
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        // تأكيد الشراء مع Google
        if (!purchase.isAcknowledged()) {
            billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build(),
                    ackResult -> Log.d(TAG, "Ack: " + ackResult.getResponseCode())
            );
        }

        UserManager.Tier tier = getTierFromProducts(purchase.getProducts());
        userManager.setTier(tier);
        saveLastVerified();

        Log.d(TAG, "✅ Purchase handled: " + tier);
        if (listener != null) {
            handler.post(() -> listener.onPurchaseSuccess(tier));
        }
    }

    // ── استرجاع والتحقق من الاشتراكات ──────────────────────────
    // هذا هو القلب - يتحقق من Google Play مباشرة

    public void restorePurchases() {
        if (billingClient == null || !billingClient.isReady()) {
            applyGracePeriod();
            return;
        }

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "Restore failed: " + result.getResponseCode());
                        applyGracePeriod();
                        return;
                    }

                    saveLastVerified(); // سجّل وقت آخر تحقق ناجح

                    // ابحث عن اشتراك فعّال
                    UserManager.Tier activeTier = UserManager.Tier.FREE;
                    if (purchases != null) {
                        for (Purchase p : purchases) {
                            if (p == null) continue;
                            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                UserManager.Tier t = getTierFromProducts(p.getProducts());
                                // PRO > PLUS > FREE
                                if (t == UserManager.Tier.PRO ||
                                   (t == UserManager.Tier.PLUS && activeTier == UserManager.Tier.FREE)) {
                                    activeTier = t;
                                }
                            }
                        }
                    }

                    UserManager.Tier currentTier = userManager.getCurrentTier();
                    final UserManager.Tier finalTier = activeTier;

                    if (activeTier == UserManager.Tier.FREE && currentTier != UserManager.Tier.FREE) {
                        // كان مشترك والحين لأ = اشتراك انتهى أو أُلغي
                        Log.w(TAG, "Subscription expired/cancelled - downgrading to FREE");
                        userManager.setTier(UserManager.Tier.FREE);
                        if (listener != null) {
                            handler.post(() -> listener.onSubscriptionExpired());
                        }
                    } else if (activeTier != currentTier) {
                        // تغيّر الـ Tier (مثلاً PLUS → PRO)
                        userManager.setTier(activeTier);
                        if (activeTier != UserManager.Tier.FREE && listener != null) {
                            handler.post(() -> listener.onPurchaseSuccess(finalTier));
                        }
                    } else {
                        Log.d(TAG, "Tier verified: " + activeTier);
                    }
                }
        );
    }

    // ── حفظ وقت آخر تحقق ────────────────────────────────────────

    private void saveLastVerified() {
        context.getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                .apply();
    }

    // ── حدد Tier من Product ID ───────────────────────────────────

    private UserManager.Tier getTierFromProducts(List<String> products) {
        if (products == null) return UserManager.Tier.FREE;
        for (String id : products) {
            if (id == null) continue;
            if (id.startsWith("pro_"))  return UserManager.Tier.PRO;
            if (id.startsWith("plus_")) return UserManager.Tier.PLUS;
        }
        return UserManager.Tier.FREE;
    }

    // ── تنظيف ────────────────────────────────────────────────────

    public void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (billingClient != null) {
            billingClient.endConnection();
            billingClient = null;
        }
        listener = null;
    }
}
