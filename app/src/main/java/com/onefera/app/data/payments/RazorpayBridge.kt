package com.onefera.app.data.payments

import android.app.Activity
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.Address
import com.onefera.app.data.shop.CheckoutSession
import com.onefera.app.data.shop.PaymentResult
import com.razorpay.Checkout
import com.razorpay.PaymentData
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a live checkout session to the Razorpay Checkout SDK and waits for its result. The SDK
 * reports back through the Activity ([com.onefera.app.MainActivity] implements
 * `PaymentResultWithDataListener` and forwards to [onSuccess] / [onError]).
 * The signature is verified server-side by `confirmPayment`, never trusted here.
 */
@Singleton
class RazorpayBridge @Inject constructor() {
    private var pending: CompletableDeferred<Result<PaymentResult>>? = null

    suspend fun pay(activity: Activity, session: CheckoutSession, address: Address, email: String?): Result<PaymentResult> {
        if (session.razorpayKeyId.isBlank() || session.razorpayOrderId.isBlank()) {
            return Result.failure(UserFacingException("Payments aren't set up yet. Please try again later."))
        }
        pending?.complete(Result.failure(UserFacingException("Payment cancelled.")))
        val deferred = CompletableDeferred<Result<PaymentResult>>()
        pending = deferred
        val options = JSONObject().apply {
            put("name", "OneFera")
            put("description", "Order ${session.orderId}")
            put("order_id", session.razorpayOrderId)
            put("currency", "INR")
            put("amount", session.amount * 100)
            put("prefill", JSONObject().apply {
                put("contact", address.phone)
                if (!email.isNullOrBlank()) put("email", email)
                put("method", when (session.method.name) { "Upi" -> "upi"; "Card" -> "card"; "NetBanking" -> "netbanking"; else -> "" })
            })
            put("theme", JSONObject().put("color", "#8B5CF6"))
            put("retry", JSONObject().put("enabled", true).put("max_count", 2))
        }
        runCatching {
            Checkout().apply { setKeyID(session.razorpayKeyId) }.open(activity, options)
        }.onFailure {
            pending = null
            return Result.failure(UserFacingException("Couldn't open the payment page.", it))
        }
        return deferred.await()
    }

    fun onSuccess(paymentId: String?, data: PaymentData?) {
        val id = paymentId ?: data?.paymentId
        pending?.complete(
            if (id.isNullOrBlank()) Result.failure(UserFacingException("Payment didn't go through."))
            else Result.success(PaymentResult(paymentId = id, signature = data?.signature.orEmpty())),
        )
        pending = null
    }

    fun onError(code: Int, description: String?) {
        // Razorpay reports code 0 (Checkout.PAYMENT_CANCELED) when the shopper closes the sheet.
        val message = if (code == 0) "Payment cancelled. Your cart is still saved." else "Payment failed. No money was taken, try again."
        pending?.complete(Result.failure(UserFacingException(message, description?.let { IllegalStateException(it) })))
        pending = null
    }

    companion object {
        /** Warms up the SDK so the payment sheet opens faster. */
        fun preload(activity: Activity) = runCatching { Checkout.preload(activity.applicationContext) }
    }
}
