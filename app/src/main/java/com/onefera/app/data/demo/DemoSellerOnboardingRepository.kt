package com.onefera.app.data.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.PayoutMethod
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.SellerApplication
import com.onefera.app.data.model.SellerBusinessType
import com.onefera.app.data.model.SellerStatus
import com.onefera.app.data.model.SellerValidation
import com.onefera.app.data.seller.SellerOnboardingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoSellerStore: DataStore<Preferences> by preferencesDataStore(name = "demo_seller")

/** Demo mode: applications are validated on the device and approved straight away. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoSellerOnboardingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val accounts: DemoBackend,
) : SellerOnboardingRepository {

    override fun application(): Flow<SellerApplication?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else context.demoSellerStore.data.map { p -> p[key(uid)]?.let(::decode) }
    }

    override suspend fun submit(application: SellerApplication): Result<SellerStatus> = runCatching {
        val uid = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")
        val errors = SellerValidation.errors(application)
        if (errors.isNotEmpty()) throw UserFacingException(errors.values.first())
        val saved = application.copy(status = SellerStatus.Approved, submittedAt = System.currentTimeMillis(), accountNumber = mask(application.accountNumber))
        context.demoSellerStore.edit { it[key(uid)] = encode(saved) }
        accounts.saveProfile(uid) { it.copy(sellerStatus = SellerStatus.Approved) }.getOrThrow()
        SellerStatus.Approved
    }

    private fun key(uid: String) = stringPreferencesKey("application_$uid")
    private fun mask(account: String) = if (account.length > 4) "•••• " + account.takeLast(4) else account

    private fun encode(a: SellerApplication): String = JSONObject().apply {
        put("storeName", a.storeName); put("legalName", a.legalName); put("businessType", a.businessType.name)
        put("category", a.category.name); put("description", a.description); put("phone", a.phone); put("email", a.email)
        put("addressLine", a.addressLine); put("city", a.city); put("state", a.state); put("pincode", a.pincode)
        put("gstin", a.gstin); put("pan", a.pan); put("payoutMethod", a.payoutMethod.name); put("upiId", a.upiId)
        put("accountHolder", a.accountHolder); put("accountNumber", a.accountNumber); put("ifsc", a.ifsc)
        put("acceptedTerms", a.acceptedTerms); put("status", a.status.name); put("submittedAt", a.submittedAt)
    }.toString()

    private fun decode(raw: String): SellerApplication? = runCatching {
        val o = JSONObject(raw)
        SellerApplication(
            storeName = o.optString("storeName"), legalName = o.optString("legalName"),
            businessType = runCatching { SellerBusinessType.valueOf(o.optString("businessType")) }.getOrDefault(SellerBusinessType.Individual),
            category = runCatching { ProductCategory.valueOf(o.optString("category")) }.getOrDefault(ProductCategory.Fashion),
            description = o.optString("description"), phone = o.optString("phone"), email = o.optString("email"),
            addressLine = o.optString("addressLine"), city = o.optString("city"), state = o.optString("state"), pincode = o.optString("pincode"),
            gstin = o.optString("gstin"), pan = o.optString("pan"),
            payoutMethod = runCatching { PayoutMethod.valueOf(o.optString("payoutMethod")) }.getOrDefault(PayoutMethod.Upi),
            upiId = o.optString("upiId"), accountHolder = o.optString("accountHolder"), accountNumber = o.optString("accountNumber"),
            ifsc = o.optString("ifsc"), acceptedTerms = o.optBoolean("acceptedTerms"),
            status = runCatching { SellerStatus.valueOf(o.optString("status")) }.getOrDefault(SellerStatus.Pending),
            submittedAt = o.optLong("submittedAt"),
        )
    }.getOrNull()
}
