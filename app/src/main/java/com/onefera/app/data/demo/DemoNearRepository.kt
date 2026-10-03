package com.onefera.app.data.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.Geo
import com.onefera.app.data.model.LatLng
import com.onefera.app.data.model.NearRadius
import com.onefera.app.data.model.NearSettings
import com.onefera.app.data.model.NearbyPerson
import com.onefera.app.data.model.NearbyStore
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.near.NearRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

private val Context.demoNearStore: DataStore<Preferences> by preferencesDataStore(name = "demo_near")

/**
 * Demo Near: the demo creators "hang out" at fixed spots around wherever the tester is, so the
 * radar and lists work anywhere (and on emulators) without a real crowd.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoNearRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val accounts: DemoBackend,
    private val catalog: DemoCatalog,
) : NearRepository {

    private fun uid(): String = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")

    override fun settings(): Flow<NearSettings> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(NearSettings())
        } else {
            context.demoNearStore.data.map { p ->
                NearSettings(visible = uid in p[VISIBLE].orEmpty(), shareStore = uid in p[STORES].orEmpty())
            }
        }
    }

    private suspend fun toggle(key: Preferences.Key<Set<String>>, uid: String, on: Boolean) {
        context.demoNearStore.edit { p -> p[key] = p[key].orEmpty().let { if (on) it + uid else it - uid } }
    }

    override suspend fun setVisible(visible: Boolean, location: LatLng?): Result<Unit> = runCatching {
        val uid = uid()
        if (visible) {
            val profile = accounts.profileNow(uid) ?: throw UserFacingException("Account not found.")
            if (profile.isMinor) throw UserFacingException("Near is for members 18 and over, to keep everyone safe.")
            if (profile.isPrivate) throw UserFacingException("Private accounts stay hidden from Near. Switch to public in Settings to appear.")
            if (location == null) throw UserFacingException("Turn on location to appear on Near.")
        }
        toggle(VISIBLE, uid, visible)
    }

    override suspend fun setStoreShared(shared: Boolean, location: LatLng?): Result<Unit> = runCatching {
        val uid = uid()
        if (shared) {
            val profile = accounts.profileNow(uid) ?: throw UserFacingException("Account not found.")
            if (profile.accountMode != AccountMode.Seller) throw UserFacingException("Switch to a Seller account to list your store.")
            if (location == null) throw UserFacingException("Turn on location to place your store.")
        }
        toggle(STORES, uid, shared)
    }

    override suspend fun refresh(location: LatLng) = Unit

    /** Offset in km (east, north) from the viewer. */
    private fun offset(from: LatLng, eastKm: Double, northKm: Double): LatLng =
        LatLng(from.lat + northKm / 110.574, from.lng + eastKm / (111.320 * cos(Math.toRadians(from.lat))))

    override suspend fun people(location: LatLng, radius: NearRadius): Result<List<NearbyPerson>> = runCatching {
        uid()
        delay(400)
        val spots = mapOf(
            "demo-aanya" to (0.6 to 0.4),
            "demo-kabir" to (-1.4 to 1.1),
            "demo-zoya" to (2.8 to -1.9),
            "demo-rohan" to (-3.5 to -2.2),
            "demo-arjun" to (6.0 to 7.5),
        )
        val profiles = accounts.profiles.first().associateBy { it.uid }
        spots.mapNotNull { (id, xy) ->
            val p = profiles[id]?.takeIf { !it.isPrivate && !it.isMinor } ?: return@mapNotNull null
            val at = Geo.coarse(offset(location, xy.first, xy.second))
            NearbyPerson(p.toSummary(), p.vibe, p.auraPoints, Geo.distanceKm(location, at), Geo.bearing(location, at), System.currentTimeMillis())
        }.filter { it.distanceKm <= radius.km }.sortedBy { it.distanceKm }
    }

    override suspend fun stores(location: LatLng, radius: NearRadius): Result<List<NearbyStore>> = runCatching {
        val me = uid()
        delay(300)
        val profiles = accounts.profiles.first().associateBy { it.uid }
        val bySeller = catalog.products.groupBy { it.sellerId }
        val spots = mutableMapOf(
            "demo-arjun" to (1.2 to -0.8),
            "demo-aanya" to (-0.9 to 2.3),
            "demo-meera" to (4.1 to 3.0),
        )
        if (me in context.demoNearStore.data.first()[STORES].orEmpty()) spots[me] = 0.0 to 0.0
        spots.mapNotNull { (id, xy) ->
            val p = profiles[id] ?: return@mapNotNull null
            val at = Geo.coarse(offset(location, xy.first, xy.second))
            val listings = bySeller[id].orEmpty()
            NearbyStore(
                seller = p.toSummary(),
                city = p.city,
                listings = listings.size,
                coverUrl = listings.firstOrNull()?.imageUrl,
                distanceKm = Geo.distanceKm(location, at),
                bearing = Geo.bearing(location, at),
            )
        }.filter { it.distanceKm <= radius.km }.sortedBy { it.distanceKm }
    }

    private companion object {
        val VISIBLE = stringSetPreferencesKey("visible")
        val STORES = stringSetPreferencesKey("stores")
    }
}
