package com.onefera.app.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.Geo
import com.onefera.app.data.model.LatLng
import com.onefera.app.data.model.NearRadius
import com.onefera.app.data.model.NearSettings
import com.onefera.app.data.model.NearbyPerson
import com.onefera.app.data.model.NearbyStore
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.near.NearRepository
import com.onefera.app.data.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Near on Firestore:
 * - `near/{uid}`   a visible person: `{ geohash, lat, lng, user, vibe, auraPoints, updatedAt }` (deleted when hidden)
 * - `stores/{uid}` a seller's store area: `{ geohash, lat, lng, seller, city, listings, coverUrl, updatedAt }`
 * Positions are rounded to ~1 km before upload. Nearby queries are geohash prefix ranges over
 * the 9 cells around the viewer, then filtered by real distance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreNearRepository @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
) : NearRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val near get() = db.collection("near")
    private val stores get() = db.collection("stores")

    override fun settings(): Flow<NearSettings> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(NearSettings())
        } else {
            combine(near.document(uid).snapshotFlow(), stores.document(uid).snapshotFlow()) { person, store ->
                NearSettings(visible = person.exists(), shareStore = store.exists())
            }
        }
    }

    override suspend fun setVisible(visible: Boolean, location: LatLng?): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        if (!visible) {
            near.document(uid).delete().await()
            return@runFriendly
        }
        val profile = users.observeProfile(uid).first() ?: throw UserFacingException("Finish setting up your profile first.")
        if (profile.isMinor) throw UserFacingException("Near is for members 18 and over, to keep everyone safe.")
        if (profile.isPrivate) throw UserFacingException("Private accounts stay hidden from Near. Switch to public in Settings to appear.")
        val at = Geo.coarse(location ?: throw UserFacingException("Turn on location to appear on Near."))
        near.document(uid).set(
            mapOf(
                "geohash" to Geo.geohash(at, 6),
                "lat" to at.lat,
                "lng" to at.lng,
                "user" to profile.toSummary().toMap(),
                "vibe" to profile.vibe,
                "auraPoints" to profile.auraPoints,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    override suspend fun setStoreShared(shared: Boolean, location: LatLng?): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        if (!shared) {
            stores.document(uid).delete().await()
            return@runFriendly
        }
        val profile = users.observeProfile(uid).first() ?: throw UserFacingException("Finish setting up your profile first.")
        if (profile.accountMode != AccountMode.Seller) throw UserFacingException("Switch to a Seller account to list your store.")
        val at = Geo.coarse(location ?: throw UserFacingException("Turn on location to place your store."))
        val listings = db.collection("products").whereEqualTo("sellerId", uid).limit(100).get().await()
        stores.document(uid).set(
            mapOf(
                "geohash" to Geo.geohash(at, 6),
                "lat" to at.lat,
                "lng" to at.lng,
                "seller" to profile.toSummary().toMap(),
                "city" to profile.city,
                "listings" to listings.size(),
                "coverUrl" to listings.documents.firstNotNullOfOrNull { (it.get("images") as? List<*>)?.firstOrNull() as? String },
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    override suspend fun refresh(location: LatLng) {
        val uid = (runCatching { auth.currentUid() }.getOrNull()) ?: return
        val doc = runCatching { near.document(uid).get().await() }.getOrNull() ?: return
        if (!doc.exists()) return
        val at = Geo.coarse(location)
        runCatching {
            near.document(uid).update(mapOf("geohash" to Geo.geohash(at, 6), "lat" to at.lat, "lng" to at.lng, "updatedAt" to FieldValue.serverTimestamp())).await()
        }
    }

    private suspend fun around(collection: String, location: LatLng, radius: NearRadius): List<DocumentSnapshot> = coroutineScope {
        Geo.coveringCells(location, radius.precision).map { cell ->
            async { db.collection(collection).orderBy("geohash").startAt(cell).endAt(cell + "~").limit(100).get().await().documents }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    override suspend fun people(location: LatLng, radius: NearRadius): Result<List<NearbyPerson>> = runFriendly {
        val uid = auth.currentUid()
        val staleBefore = System.currentTimeMillis() - STALE_MS
        around("near", location, radius)
            .filter { it.id != uid }
            .mapNotNull { d ->
                val at = LatLng(d.getDouble("lat") ?: return@mapNotNull null, d.getDouble("lng") ?: return@mapNotNull null)
                val updated = d.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                if (updated < staleBefore) return@mapNotNull null
                NearbyPerson(
                    user = (d.get("user") as? Map<*, *>).toUserSummary(),
                    vibe = d.getString("vibe").orEmpty(),
                    auraPoints = d.getLong("auraPoints")?.toInt() ?: 0,
                    distanceKm = Geo.distanceKm(location, at),
                    bearing = Geo.bearing(location, at),
                    updatedAt = updated,
                )
            }
            .filter { it.distanceKm <= radius.km }
            .sortedBy { it.distanceKm }
    }

    override suspend fun stores(location: LatLng, radius: NearRadius): Result<List<NearbyStore>> = runFriendly {
        around("stores", location, radius)
            .mapNotNull { d ->
                val at = LatLng(d.getDouble("lat") ?: return@mapNotNull null, d.getDouble("lng") ?: return@mapNotNull null)
                NearbyStore(
                    seller = (d.get("seller") as? Map<*, *>).toUserSummary(),
                    city = d.getString("city").orEmpty(),
                    listings = d.getLong("listings")?.toInt() ?: 0,
                    coverUrl = d.getString("coverUrl"),
                    distanceKm = Geo.distanceKm(location, at),
                    bearing = Geo.bearing(location, at),
                )
            }
            .filter { it.distanceKm <= radius.km }
            .sortedBy { it.distanceKm }
    }

    private companion object {
        /** People who haven't opened Near in a day drop off the list. */
        const val STALE_MS = 24 * 60 * 60 * 1000L
    }
}
