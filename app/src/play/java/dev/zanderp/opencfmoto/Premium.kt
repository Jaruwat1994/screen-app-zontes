// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Play build: premium dash templates are a one-time, non-consumable in-app product ([PRODUCT], created
 * in Play Console → Monetize → In-app products). The owned state is cached in SharedPreferences so it
 * works offline; [refresh] re-reads it from Play (restores on reinstall, re-locks after a refund).
 * The github build has its own [Premium] that is always unlocked.
 */
object Premium {
    const val PRODUCT = "zride_premium"
    private const val PREFS = "premium"
    private const val KEY = "unlocked"

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var client: BillingClient? = null
    private var connecting = false
    private val waiting = mutableListOf<(BillingClient?) -> Unit>()
    private var details: ProductDetails? = null
    /** Result callback of the purchase flow in progress (one at a time). */
    private var pending: ((Boolean) -> Unit)? = null

    private val updates = PurchasesUpdatedListener { r, list ->
        main.post {
            val cb = pending.also { pending = null }
            val ok = when (r.responseCode) {
                BillingClient.BillingResponseCode.OK -> list.orEmpty().any { handle(it) }
                // Bought before (other device / reinstall): Play says so instead of charging again.
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { appCtx?.let { save(it, true); refresh(it) }; true }
                else -> false
            }
            if (!ok && r.responseCode == BillingClient.BillingResponseCode.OK) toast(R.string.premium_pending)
            if (ok) toast(R.string.premium_thanks)
            cb?.invoke(ok)
        }
    }

    fun unlocked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, false)

    /** Re-reads ownership from Play and acknowledges any unacknowledged purchase. Call on app start / resume. */
    fun refresh(ctx: Context) {
        connect(ctx.applicationContext) { c ->
            c ?: return@connect
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { r, list ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
                main.post {
                    val owned = list.filter { handle(it) }.isNotEmpty()
                    if (!owned) save(ctx, false) // refunded / revoked
                }
            }
        }
    }

    /** Launches Play's purchase sheet; [onResult] runs on the main thread (true = unlocked). */
    fun purchase(activity: Activity, onResult: (Boolean) -> Unit) {
        if (unlocked(activity)) return onResult(true)
        withDetails(activity) { c, pd ->
            if (activity.isFinishing || activity.isDestroyed) return@withDetails onResult(false)
            if (c == null || pd == null) {
                toast(R.string.premium_unavailable)
                return@withDetails onResult(false)
            }
            pending = onResult
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build())
            ).build()
            val r = c.launchBillingFlow(activity, flow)
            if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                pending = null
                LogBus.log("[PREMIUM] launchBillingFlow failed: ${r.responseCode} ${r.debugMessage}")
                onResult(false)
            }
        }
    }

    /** Runs [onUnlocked] now if premium, otherwise explains premium and offers to buy it. */
    fun requireOrPrompt(activity: Activity, onUnlocked: () -> Unit) {
        if (unlocked(activity)) return onUnlocked()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.premium_title)
            .setMessage(R.string.premium_body)
            .setPositiveButton(R.string.premium_buy) { _, _ -> purchase(activity) { if (it) onUnlocked() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        withDetails(activity) { _, pd ->
            val price = pd?.oneTimePurchaseOfferDetails?.formattedPrice ?: return@withDetails
            if (dialog.isShowing) dialog.getButton(DialogInterface.BUTTON_POSITIVE).text =
                activity.getString(R.string.premium_buy_price, price)
        }
    }

    /** True if [p] is a completed premium purchase; caches the unlock and acknowledges it (Play refunds unacknowledged ones after 3 days). */
    private fun handle(p: Purchase): Boolean {
        if (PRODUCT !in p.products || p.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        appCtx?.let { save(it, true) }
        if (!p.isAcknowledged) client?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        ) { r -> LogBus.log("[PREMIUM] acknowledge: ${r.responseCode} ${r.debugMessage}") }
        return true
    }

    /** ProductDetails for [PRODUCT] (cached after the first hit); callback on the main thread, nulls if unavailable. */
    private fun withDetails(ctx: Context, then: (BillingClient?, ProductDetails?) -> Unit) {
        connect(ctx.applicationContext) { c ->
            if (c == null) return@connect then(null, null)
            details?.let { return@connect then(c, it) }
            val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )).build()
            c.queryProductDetailsAsync(params) { r, result ->
                val pd = if (r.responseCode == BillingClient.BillingResponseCode.OK) result.productDetailsList.firstOrNull() else null
                main.post { details = pd ?: details; then(c, pd) }
            }
        }
    }

    private fun save(ctx: Context, on: Boolean) {
        if (unlocked(ctx) != on) LogBus.log("[PREMIUM] unlocked=$on")
        prefs(ctx).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toast(res: Int) {
        appCtx?.let { Toast.makeText(it, res, Toast.LENGTH_LONG).show() }
    }

    // Same connect-once-and-queue pattern as Support (separate client: its listener consumes donations).
    private fun connect(ctx: Context, then: (BillingClient?) -> Unit) {
        main.post {
            appCtx = ctx
            val c = client ?: BillingClient.newBuilder(ctx)
                .setListener(updates)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
                .also { client = it }
            if (c.isReady) return@post then(c)
            waiting += then
            if (connecting) return@post
            connecting = true
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    main.post {
                        connecting = false
                        val ok = r.responseCode == BillingClient.BillingResponseCode.OK
                        if (!ok) LogBus.log("[PREMIUM] billing unavailable: ${r.responseCode} ${r.debugMessage}")
                        val cbs = waiting.toList()
                        waiting.clear()
                        cbs.forEach { it(if (ok) c else null) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    main.post { connecting = false }
                }
            })
        }
    }
}
