package com.ardeman.krida.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.ardeman.krida.R

/**
 * Play Billing for the one non-consumable product, "Hapus iklan". Ownership is re-read from
 * Play on every [refresh], so a reinstall or a new device restores it without a button.
 * Callbacks are moved to the main thread, like the transports.
 */
internal object Billing : PurchasesUpdatedListener {

    /** Localized price from Play, or null while the product is unknown (not set up, offline). */
    var price by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    private var product: ProductDetails? = null
    /** Activity waiting for [product] so a closed-tester tap still opens Play's sheet. */
    private var pendingBuy: Activity? = null
    private lateinit var productId: String
    private lateinit var onOwned: (Boolean) -> Unit

    fun connect(context: Context, onOwned: (Boolean) -> Unit) {
        if (client != null) return
        this.onOwned = onOwned
        productId = context.getString(R.string.iap_remove_ads)
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also {
                it.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            refresh()
                            loadProduct()
                        }
                    }

                    override fun onBillingServiceDisconnected() = Unit
                })
            }
    }

    /** Re-reads what this account owns. */
    fun refresh() {
        val c = client?.takeIf { it.isReady } ?: return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) main.post { handle(purchases) }
        }
    }

    fun buy(activity: Activity) {
        val c = client ?: return
        val p = product
        if (p == null) {
            // Closed / first launch: product may still be loading — open the sheet when ready.
            pendingBuy = activity
            loadProduct()
            refresh()
            return
        }
        pendingBuy = null
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build()))
            .build()
        c.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> main.post { handle(purchases.orEmpty()) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> Unit // cancelled or failed: nothing changes
        }
    }

    private fun loadProduct() {
        val c = client?.takeIf { it.isReady } ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        c.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val p = details.productDetailsList.firstOrNull()
            main.post {
                product = p
                price = p?.oneTimePurchaseOfferDetails?.formattedPrice
                pendingBuy?.let { buy(it) }
            }
        }
    }

    private fun handle(purchases: List<Purchase>) {
        val mine = purchases.filter { productId in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Unacknowledged purchases are refunded by Play after three days.
        mine.filterNot { it.isAcknowledged }.forEach { p ->
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        onOwned(mine.isNotEmpty())
    }
}
