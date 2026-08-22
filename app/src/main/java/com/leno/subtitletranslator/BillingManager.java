package com.leno.subtitletranslator;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.*;

import java.util.ArrayList;
import java.util.List;

public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";

    private final Context context;
    private final UserManager userManager;
    private BillingClient billingClient;
    private OnBillingListener listener;

    public interface OnBillingListener {
        void onPurchaseSuccess(UserManager.Tier tier);
        void onPurchaseFailed(String message);
    }

    public BillingManager(Context context, UserManager userManager) {
        this.context = context;
        this.userManager = userManager;
    }

    public void init(OnBillingListener listener) {
        this.listener = listener;

        // Billing Library 9.x:
        // enablePendingPurchases() بدون معاملات لم يعد مدعوماً.
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
            public void onBillingSetupFinished(
                    @NonNull BillingResult result) {

                if (result.getResponseCode()
                        == BillingClient.BillingResponseCode.OK) {

                    Log.d(TAG, "✅ Billing connected");

                    restorePurchases();
                } else {

                    Log.e(
                            TAG,
                            "Billing setup failed: "
                                    + result.getResponseCode()
                                    + " - "
                                    + result.getDebugMessage()
                    );
                }
            }

            @Override
            public void onBillingServiceDisconnected() {

                Log.w(
                        TAG,
                        "Billing disconnected"
                );
            }
        });
    }

    // ── شراء اشتراك ─────────────────────────────────────────────

    public void launchPurchase(
            Activity activity,
            String productId) {

        if (billingClient == null) {
            Log.e(TAG, "BillingClient is null");
            if (listener != null) {
                listener.onPurchaseFailed(
                        "خدمة الدفع غير جاهزة"
                );
            }
            return;
        }

        List<QueryProductDetailsParams.Product> products =
                new ArrayList<>();

        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(
                                BillingClient.ProductType.SUBS
                        )
                        .build()
        );

        billingClient.queryProductDetailsAsync(

                QueryProductDetailsParams.newBuilder()
                        .setProductList(products)
                        .build(),

                (billingResult, queryProductDetailsResult) -> {

                    // ── تحقق من نتيجة الاستعلام ──

                    if (billingResult.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {

                        Log.e(
                                TAG,
                                "Product query failed: "
                                        + billingResult.getResponseCode()
                                        + " - "
                                        + billingResult.getDebugMessage()
                        );

                        if (listener != null) {
                            listener.onPurchaseFailed(
                                    "تعذر جلب المنتج"
                            );
                        }

                        return;
                    }

                    // Billing 8/9:
                    // queryProductDetailsAsync أصبح يرجع
                    // QueryProductDetailsResult وليس List مباشرة.

                    if (queryProductDetailsResult == null) {

                        Log.e(
                                TAG,
                                "QueryProductDetailsResult is null"
                        );

                        return;
                    }

                    List<ProductDetails> productDetailsList =
                            queryProductDetailsResult
                                    .getProductDetailsList();

                    // ── تحقق من المنتجات ──

                    if (productDetailsList == null
                            || productDetailsList.isEmpty()) {

                        Log.e(
                                TAG,
                                "Product not found: "
                                        + productId
                        );

                        if (listener != null) {
                            listener.onPurchaseFailed(
                                    "المنتج غير متوفر"
                            );
                        }

                        return;
                    }

                    // المنتج المطلوب

                    ProductDetails product =
                            productDetailsList.get(0);

                    // ── عروض الاشتراك ──

                    List<ProductDetails.SubscriptionOfferDetails> offers =
                            product.getSubscriptionOfferDetails();

                    if (offers == null
                            || offers.isEmpty()) {

                        Log.e(
                                TAG,
                                "No subscription offers: "
                                        + productId
                        );

                        if (listener != null) {
                            listener.onPurchaseFailed(
                                    "لا توجد عروض اشتراك متاحة"
                            );
                        }

                        return;
                    }

                    // اختيار أفضل عرض

                    ProductDetails.SubscriptionOfferDetails selectedOffer =
                            selectBestOffer(offers);

                    if (selectedOffer == null) {

                        Log.e(
                                TAG,
                                "No valid subscription offer"
                        );

                        if (listener != null) {
                            listener.onPurchaseFailed(
                                    "لا يوجد عرض اشتراك صالح"
                            );
                        }

                        return;
                    }

                    // ── تجهيز Billing Flow ──

                    List<BillingFlowParams.ProductDetailsParams> params =
                            new ArrayList<>();

                    params.add(
                            BillingFlowParams.ProductDetailsParams
                                    .newBuilder()
                                    .setProductDetails(product)
                                    .setOfferToken(
                                            selectedOffer.getOfferToken()
                                    )
                                    .build()
                    );

                    BillingFlowParams billingFlowParams =
                            BillingFlowParams.newBuilder()
                                    .setProductDetailsParamsList(params)
                                    .build();

                    BillingResult launchResult =
                            billingClient.launchBillingFlow(
                                    activity,
                                    billingFlowParams
                            );

                    Log.d(
                            TAG,
                            "Billing flow launched: "
                                    + launchResult.getResponseCode()
                    );
                }
        );
    }

    // ── اختيار أفضل عرض للمستخدم ─────────────────────────────────

    private ProductDetails.SubscriptionOfferDetails selectBestOffer(
            List<ProductDetails.SubscriptionOfferDetails> offers) {

        if (offers == null || offers.isEmpty()) {
            return null;
        }

        /*
         * Google Play يرجّع فقط العروض التي المستخدم مؤهل لها.
         *
         * إذا وجدنا عرضاً يبدأ بمرحلة مجانية،
         * نفضله على العرض الأساسي.
         */

        for (ProductDetails.SubscriptionOfferDetails offer : offers) {

            if (offer == null
                    || offer.getPricingPhases() == null) {
                continue;
            }

            List<ProductDetails.PricingPhase> phases =
                    offer.getPricingPhases()
                            .getPricingPhaseList();

            if (phases == null || phases.isEmpty()) {
                continue;
            }

            ProductDetails.PricingPhase firstPhase =
                    phases.get(0);

            if (firstPhase != null
                    && firstPhase.getPriceAmountMicros() == 0) {

                return offer;
            }
        }

        // لا توجد تجربة مجانية متاحة
        return offers.get(0);
    }

    // ── استقبال نتيجة الشراء ────────────────────────────────────

    @Override
    public void onPurchasesUpdated(
            @NonNull BillingResult result,
            List<Purchase> purchases) {

        if (result.getResponseCode()
                == BillingClient.BillingResponseCode.OK
                && purchases != null) {

            for (Purchase purchase : purchases) {

                if (purchase != null) {
                    handlePurchase(purchase);
                }
            }

        } else {

            Log.w(
                    TAG,
                    "Purchase failed: "
                            + result.getResponseCode()
                            + " - "
                            + result.getDebugMessage()
            );

            // إلغاء المستخدم للشراء ليس خطأ تقنياً
            if (result.getResponseCode()
                    == BillingClient.BillingResponseCode.USER_CANCELED) {

                if (listener != null) {
                    listener.onPurchaseFailed(
                            "تم إلغاء الشراء"
                    );
                }

                return;
            }

            if (listener != null) {
                listener.onPurchaseFailed(
                        "فشل الشراء"
                );
            }
        }
    }

    // ── معالجة الشراء ───────────────────────────────────────────

    private void handlePurchase(Purchase purchase) {

        if (purchase == null) {
            return;
        }

        if (purchase.getPurchaseState()
                != Purchase.PurchaseState.PURCHASED) {

            return;
        }

        // تأكيد الشراء

        if (!purchase.isAcknowledged()) {

            billingClient.acknowledgePurchase(

                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(
                                    purchase.getPurchaseToken()
                            )
                            .build(),

                    ackResult -> {

                        Log.d(
                                TAG,
                                "Ack: "
                                        + ackResult.getResponseCode()
                        );
                    }
            );
        }

        // حدد Tier حسب المنتج

        UserManager.Tier tier =
                getTierFromProducts(
                        purchase.getProducts()
                );

        userManager.setTier(tier);

        Log.d(
                TAG,
                "✅ Subscribed: "
                        + tier
        );

        if (listener != null) {

            listener.onPurchaseSuccess(tier);
        }
    }

    // ── استرجاع الاشتراكات السابقة ──────────────────────────────

    public void restorePurchases() {

        if (billingClient == null) {
            Log.w(
                    TAG,
                    "Cannot restore: BillingClient is null"
            );
            return;
        }

        billingClient.queryPurchasesAsync(

                QueryPurchasesParams.newBuilder()
                        .setProductType(
                                BillingClient.ProductType.SUBS
                        )
                        .build(),

                (result, purchases) -> {

                    if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {

                        Log.e(
                                TAG,
                                "Restore failed: "
                                        + result.getResponseCode()
                        );

                        return;
                    }

                    if (purchases == null) {
                        return;
                    }

                    for (Purchase p : purchases) {

                        if (p == null) {
                            continue;
                        }

                        if (p.getPurchaseState()
                                == Purchase.PurchaseState.PURCHASED) {

                            UserManager.Tier tier =
                                    getTierFromProducts(
                                            p.getProducts()
                                    );

                            userManager.setTier(tier);

                            Log.d(
                                    TAG,
                                    "Restored: "
                                            + tier
                            );
                        }
                    }
                }
        );
    }

    // ── حدد Tier من Product ID ───────────────────────────────────

    private UserManager.Tier getTierFromProducts(
            List<String> products) {

        if (products == null) {
            return UserManager.Tier.FREE;
        }

        for (String id : products) {

            if (id == null) {
                continue;
            }

            if (id.startsWith("pro_")) {
                return UserManager.Tier.PRO;
            }

            if (id.startsWith("plus_")) {
                return UserManager.Tier.PLUS;
            }
        }

        return UserManager.Tier.FREE;
    }

    // ── تنظيف BillingClient ─────────────────────────────────────

    public void destroy() {

        if (billingClient != null) {

            billingClient.endConnection();

            billingClient = null;
        }

        listener = null;
    }
}
