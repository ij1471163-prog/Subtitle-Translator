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
        this.context     = context;
        this.userManager = userManager;
    }

    public void init(OnBillingListener listener) {
        this.listener = listener;
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "✅ Billing connected");
                    restorePurchases();
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected");
            }
        });
    }

    // ── شراء اشتراك ─────────────────────────────────────────────
    public void launchPurchase(Activity activity, String productId) {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS)
            .build());

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
            (billingResult, productDetailsList) -> {
                if (productDetailsList == null || productDetailsList.isEmpty()) {
                    Log.e(TAG, "Product not found: " + productId);
                    return;
                }
                ProductDetails product = productDetailsList.get(0);
                List<ProductDetails.SubscriptionOfferDetails> offers =
                    product.getSubscriptionOfferDetails();
                if (offers == null || offers.isEmpty()) return;

                List<BillingFlowParams.ProductDetailsParams> params = new ArrayList<>();
                params.add(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(product)
                    .setOfferToken(offers.get(0).getOfferToken())
                    .build());

                billingClient.launchBillingFlow(activity,
                    BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(params)
                        .build());
            });
    }

    // ── استقبال نتيجة الشراء ────────────────────────────────────
    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                   List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else {
            if (listener != null)
                listener.onPurchaseFailed("فشل الشراء");
        }
    }

    // ── معالجة الشراء ───────────────────────────────────────────
    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        // تأكيد الشراء
        if (!purchase.isAcknowledged()) {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build(),
                ackResult -> Log.d(TAG, "Ack: " + ackResult.getResponseCode()));
        }

        // حدد الـ Tier حسب المنتج
        UserManager.Tier tier = getTierFromProducts(purchase.getProducts());
        userManager.setTier(tier);
        Log.d(TAG, "✅ Subscribed: " + tier);

        if (listener != null) listener.onPurchaseSuccess(tier);
    }

    // ── استرجاع الاشتراكات السابقة ──────────────────────────────
    public void restorePurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            (result, purchases) -> {
                if (purchases == null) return;
                for (Purchase p : purchases) {
                    if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        UserManager.Tier tier = getTierFromProducts(p.getProducts());
                        userManager.setTier(tier);
                        Log.d(TAG, "Restored: " + tier);
                    }
                }
            });
    }

    // ── حدد Tier من Product ID ───────────────────────────────────
    private UserManager.Tier getTierFromProducts(List<String> products) {
        if (products == null) return UserManager.Tier.FREE;
        for (String id : products) {
            if (id.startsWith("pro_")) return UserManager.Tier.PRO;
            if (id.startsWith("plus_")) return UserManager.Tier.PLUS;
        }
        return UserManager.Tier.FREE;
    }

    public void destroy() {
        if (billingClient != null) billingClient.endConnection();
    }
}
