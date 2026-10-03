package com.onefera.app.data.payments

import android.app.Activity
import android.content.Context
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
import com.google.firebase.functions.FirebaseFunctions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.BackendConfig
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.MembershipPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Memberships through Google Play Billing (required by Play for digital subscriptions).
 * Products: `onefera_plus_monthly` and `onefera_seller_pro_monthly`, created in Play Console.
 * Every purchase is verified server-side (`verifyPlaySubscription`) before it is acknowledged,
 * and bound to the OneFera account through the obfuscated account id.
 */
@Singleton
class PlayBilling @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val config: BackendConfig,
) : PurchasesUpdatedListener {

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }
    private val functions by lazy { FirebaseFunctions.getInstance("asia-south1") }
    private var pending: CompletableDeferred<Result<List<Purchase>>>? = null

    /** Play Billing is only used with the live backend; demo mode simulates memberships. */
    val supported: Boolean get() = config.isFirebaseEnabled

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        } ?: false
    }

    /** Store listings for the plans that are set up in Play Console (empty when billing is unavailable). */
    suspend fun products(): Map<MembershipPlan, ProductDetails> {
        if (!supported || !connect()) return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PRODUCT_IDS.keys.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
                },
            )
            .build()
        val details = suspendCancellableCoroutine<List<ProductDetails>> { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) found.productDetailsList else emptyList())
            }
        }
        return details.mapNotNull { d -> PRODUCT_IDS[d.productId]?.let { it to d } }.toMap()
    }

    /** Runs the Play purchase sheet, then verifies and acknowledges the subscription. */
    suspend fun purchase(activity: Activity, plan: MembershipPlan, details: ProductDetails): Result<Unit> = runCatching {
        val uid = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: throw UserFacingException("${plan.label} isn't available right now.")
        val deferred = CompletableDeferred<Result<List<Purchase>>>()
        pending = deferred
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build()),
            )
            .setObfuscatedAccountId(accountHash(uid))
            .build()
        val launch = client.launchBillingFlow(activity, flow)
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            pending = null
            throw UserFacingException("Google Play couldn't start the purchase. Try again.")
        }
        val purchases = deferred.await().getOrThrow()
        purchases.forEach { verifyAndAcknowledge(it) }
    }

    /** Re-checks purchases made while the app was closed (or not yet acknowledged). */
    suspend fun syncPurchases() {
        if (!supported || !connect()) return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
            client.queryPurchasesAsync(params) { result, list -> if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList()) }
        }
        purchases.filter { !it.isAcknowledged }.forEach { runCatching { verifyAndAcknowledge(it) } }
    }

    private suspend fun verifyAndAcknowledge(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            throw UserFacingException("Your payment is pending. Your membership starts as soon as it clears.")
        }
        val productId = purchase.products.firstOrNull { it in PRODUCT_IDS } ?: return
        functions.getHttpsCallable("verifyPlaySubscription")
            .call(mapOf("productId" to productId, "purchaseToken" to purchase.purchaseToken))
            .await()
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            suspendCancellableCoroutine<Unit> { cont -> client.acknowledgePurchase(params) { if (cont.isActive) cont.resume(Unit) } }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val outcome = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> Result.success(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> Result.failure(UserFacingException("Purchase cancelled."))
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> Result.failure(UserFacingException("You already have this membership. Restoring it…"))
            else -> Result.failure(UserFacingException("Google Play couldn't complete the purchase."))
        }
        pending?.complete(outcome)
        pending = null
    }

    companion object {
        val PRODUCT_IDS = mapOf(
            "onefera_plus_monthly" to MembershipPlan.Plus,
            "onefera_seller_pro_monthly" to MembershipPlan.SellerPro,
        )

        /** Same as `accountHash()` in Cloud Functions. */
        fun accountHash(uid: String): String =
            MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }.take(64)
    }
}
