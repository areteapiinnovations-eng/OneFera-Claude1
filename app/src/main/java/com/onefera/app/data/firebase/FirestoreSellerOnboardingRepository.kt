package com.onefera.app.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.PayoutMethod
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.SellerApplication
import com.onefera.app.data.model.SellerBusinessType
import com.onefera.app.data.model.SellerStatus
import com.onefera.app.data.model.SellerValidation
import com.onefera.app.data.seller.SellerOnboardingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `sellerApplications/{uid}` is readable by the applicant only and written only by the
 * `submitSellerApplication` callable, which validates everything again and decides the status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreSellerOnboardingRepository @Inject constructor(private val auth: AuthRepository) : SellerOnboardingRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance("asia-south1") }

    override fun application(): Flow<SellerApplication?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(null)
        else db.collection("sellerApplications").document(uid).snapshotFlow().map { if (it.exists()) it.toApplication() else null }
    }

    override suspend fun submit(application: SellerApplication): Result<SellerStatus> = runFriendly {
        auth.currentUid()
        val errors = SellerValidation.errors(application)
        if (errors.isNotEmpty()) throw UserFacingException(errors.values.first())
        val a = application
        val payload = mapOf(
            "storeName" to a.storeName.trim(),
            "legalName" to a.legalName.trim(),
            "businessType" to a.businessType.name,
            "category" to a.category.name,
            "description" to a.description.trim(),
            "phone" to a.phone.trim(),
            "email" to a.email.trim(),
            "addressLine" to a.addressLine.trim(),
            "city" to a.city.trim(),
            "state" to a.state.trim(),
            "pincode" to a.pincode.trim(),
            "gstin" to a.gstin.trim().uppercase(),
            "pan" to a.pan.trim().uppercase(),
            "payoutMethod" to a.payoutMethod.name,
            "upiId" to a.upiId.trim(),
            "accountHolder" to a.accountHolder.trim(),
            "accountNumber" to a.accountNumber.trim(),
            "ifsc" to a.ifsc.trim().uppercase(),
            "acceptedTerms" to a.acceptedTerms,
        )
        val result = try {
            functions.getHttpsCallable("submitSellerApplication").call(payload).await().data as? Map<*, *>
        } catch (e: FirebaseFunctionsException) {
            throw UserFacingException(e.message ?: "Couldn't submit your registration.", e)
        }
        runCatching { SellerStatus.valueOf(result?.get("status") as? String ?: "") }.getOrDefault(SellerStatus.Pending)
    }
}

private fun DocumentSnapshot.toApplication(): SellerApplication = SellerApplication(
    storeName = getString("storeName").orEmpty(),
    legalName = getString("legalName").orEmpty(),
    businessType = runCatching { SellerBusinessType.valueOf(getString("businessType").orEmpty()) }.getOrDefault(SellerBusinessType.Individual),
    category = runCatching { ProductCategory.valueOf(getString("category").orEmpty()) }.getOrDefault(ProductCategory.Fashion),
    description = getString("description").orEmpty(),
    phone = getString("phone").orEmpty(),
    email = getString("email").orEmpty(),
    addressLine = getString("addressLine").orEmpty(),
    city = getString("city").orEmpty(),
    state = getString("state").orEmpty(),
    pincode = getString("pincode").orEmpty(),
    gstin = getString("gstin").orEmpty(),
    pan = getString("pan").orEmpty(),
    payoutMethod = runCatching { PayoutMethod.valueOf(getString("payoutMethod").orEmpty()) }.getOrDefault(PayoutMethod.Upi),
    upiId = getString("upiId").orEmpty(),
    accountHolder = getString("accountHolder").orEmpty(),
    // Only the last four digits are stored readable on the device side.
    accountNumber = getString("accountNumberMasked").orEmpty(),
    ifsc = getString("ifsc").orEmpty(),
    acceptedTerms = getBoolean("acceptedTerms") ?: false,
    status = runCatching { SellerStatus.valueOf(getString("status").orEmpty()) }.getOrDefault(SellerStatus.Pending),
    rejectionReason = getString("rejectionReason").orEmpty(),
    submittedAt = getTimestamp("submittedAt")?.toDate()?.time ?: 0L,
)
