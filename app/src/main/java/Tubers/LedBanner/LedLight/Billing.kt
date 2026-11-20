package Tubers.LedBanner.LedLight

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing for the one-time "LED Banner PRO" unlock.
 *
 * Play Console setup: Monetize → Products → One-time products → create product id "pro_lifetime".
 * Events sent to the web UI: "pro" (Boolean), "price" (String),
 * "purchase" ("pending" | "cancel" | "error" | "unavailable"), "restore" (Boolean).
 */
class Billing(
    context: Context,
    private val emit: (event: String, value: Any?) -> Unit,
) : PurchasesUpdatedListener {

    companion object {
        const val PRO_ID = "pro_lifetime"
    }

    private val prefs = context.getSharedPreferences("led_banner", Context.MODE_PRIVATE)

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var product: ProductDetails? = null

    /** Localized price from Google Play, e.g. "SAR 18.99". Empty until loaded. */
    @Volatile
    var price: String = prefs.getString("price", "") ?: ""
        private set

    /** Cached so PRO works offline. Re-verified with Google Play on every app start/resume. */
    fun isPro(): Boolean = prefs.getBoolean("pro", false)

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    loadProduct()
                    queryOwned(notify = false)
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() reconnects automatically on the next call.
            }
        })
    }

    fun refresh() {
        if (client.isReady) queryOwned(notify = false)
    }

    fun end() = client.endConnection()

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()

        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            val p = details.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            product = p
            p.oneTimePurchaseOfferDetails?.formattedPrice?.let {
                price = it
                prefs.edit().putString("price", it).apply()
                emit("price", it)
            }
        }
    }

    fun buy(activity: Activity) {
        val p = product
        if (!client.isReady || p == null) {
            emit("purchase", "unavailable")
            if (client.isReady) loadProduct() else start()
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build())
            ).build()
        client.launchBillingFlow(activity, flow)
    }

    fun restore() {
        if (client.isReady) queryOwned(notify = true) else {
            emit("restore", false)
            start()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases?.forEach { handle(it) }
            BillingResponseCode.USER_CANCELED -> emit("purchase", "cancel")
            BillingResponseCode.ITEM_ALREADY_OWNED -> queryOwned(notify = true)
            else -> emit("purchase", "error")
        }
    }

    private fun handle(purchase: Purchase) {
        if (PRO_ID !in purchase.products) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                setPro(true)
                // Purchases must be acknowledged within 3 days or Google refunds them.
                if (!purchase.isAcknowledged) {
                    val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    client.acknowledgePurchase(ack) { }
                }
            }
            Purchase.PurchaseState.PENDING -> emit("purchase", "pending")
            else -> Unit
        }
    }

    private fun queryOwned(notify: Boolean) {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, list ->
            if (result.responseCode != BillingResponseCode.OK) {
                if (notify) emit("restore", false)
                return@queryPurchasesAsync
            }
            val owned = list.firstOrNull { PRO_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (owned != null) handle(owned) else setPro(false) // also removes PRO after a refund
            if (notify) emit("restore", owned != null)
        }
    }

    private fun setPro(value: Boolean) {
        prefs.edit().putBoolean("pro", value).apply()
        emit("pro", value)
    }
}
