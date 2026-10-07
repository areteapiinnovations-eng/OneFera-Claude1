package com.onefera.app.data.near

import com.onefera.app.data.model.LatLng
import com.onefera.app.data.model.NearRadius
import com.onefera.app.data.model.NearSettings
import com.onefera.app.data.model.NearbyPerson
import com.onefera.app.data.model.NearbyStore
import kotlinx.coroutines.flow.Flow

/**
 * Near: people who chose to be visible and seller stores around you. Only approximate
 * (~1 km) positions are stored, under-18s are never listed, and visibility is off by default.
 */
interface NearRepository {
    fun settings(): Flow<NearSettings>

    /** Shows or hides the signed-in user on Near. [location] is required to turn it on. */
    suspend fun setVisible(visible: Boolean, location: LatLng?): Result<Unit>

    /** Sellers: lists or removes their store (at its approximate area) on Near. */
    suspend fun setStoreShared(shared: Boolean, location: LatLng?): Result<Unit>

    /** Refreshes the user's approximate position while visible. */
    suspend fun refresh(location: LatLng)

    /**
     * People visible around [location], kept up to date: someone turning Near on, moving or
     * hiding shows up without a manual refresh. Emits a failure if the query can't run.
     */
    fun people(location: LatLng, radius: NearRadius): Flow<Result<List<NearbyPerson>>>
    suspend fun stores(location: LatLng, radius: NearRadius): Result<List<NearbyStore>>
}
