package com.onefera.app.data.seller

import com.onefera.app.data.model.SellerApplication
import com.onefera.app.data.model.SellerStatus
import kotlinx.coroutines.flow.Flow

/** Becoming a seller: register a business, wait for approval, then switch to Seller mode. */
interface SellerOnboardingRepository {
    /** The signed-in user's application, or null if they never applied. */
    fun application(): Flow<SellerApplication?>

    /** Validates and submits (or resubmits) the application; returns the resulting status. */
    suspend fun submit(application: SellerApplication): Result<SellerStatus>
}
