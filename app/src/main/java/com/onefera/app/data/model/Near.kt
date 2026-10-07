package com.onefera.app.data.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A coarse location. Near only ever stores coordinates rounded to ~1 km (see [Geo.coarse]). */
data class LatLng(val lat: Double, val lng: Double)

/** Someone who chose to be visible on Near, with their approximate distance. */
data class NearbyPerson(
    val user: UserSummary,
    val vibe: String,
    val auraPoints: Int,
    val distanceKm: Double,
    /** Degrees clockwise from north, for the radar. */
    val bearing: Double,
    val updatedAt: Long,
)

/** A seller store with a pickup/area location. */
data class NearbyStore(
    val seller: UserSummary,
    val city: String,
    val listings: Int,
    val coverUrl: String?,
    val distanceKm: Double,
    val bearing: Double,
)

enum class NearRadius(val km: Int, val label: String, val precision: Int) {
    Close(2, "2 km", 5),
    // Precision-5 cells are ~4.9 km wide (narrower in longitude away from the equator), so the
    // 9-cell block only covers ~4 km around you; 5 km needs the next size up.
    Around(5, "5 km", 4),
    City(15, "15 km", 4),
}

/** Settings for Near, kept on the user's own document. */
data class NearSettings(val visible: Boolean = false, val shareStore: Boolean = false)

object Geo {
    private const val EARTH_KM = 6371.0
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Rounds to 2 decimals (~1.1 km) so exact homes are never stored or shown. */
    fun coarse(p: LatLng): LatLng = LatLng((p.lat * 100).roundToInt() / 100.0, (p.lng * 100).roundToInt() / 100.0)

    fun distanceKm(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(from: LatLng, to: LatLng): Double {
        val phi1 = Math.toRadians(from.lat)
        val phi2 = Math.toRadians(to.lat)
        val dLambda = Math.toRadians(to.lng - from.lng)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** "~3 km", "<1 km". Distances are approximate on purpose. */
    fun label(km: Double): String = when {
        km < 1 -> "<1 km away"
        km < 10 -> "~${km.roundToInt()} km away"
        else -> "~${(km / 5).roundToInt() * 5} km away"
    }

    /** Standard geohash of [precision] characters. */
    fun geohash(p: LatLng, precision: Int): String {
        var latLo = -90.0
        var latHi = 90.0
        var lngLo = -180.0
        var lngHi = 180.0
        val out = StringBuilder()
        var bit = 0
        var ch = 0
        var even = true
        while (out.length < precision) {
            if (even) {
                val mid = (lngLo + lngHi) / 2
                if (p.lng >= mid) { ch = ch or (16 shr bit); lngLo = mid } else lngHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (p.lat >= mid) { ch = ch or (16 shr bit); latLo = mid } else latHi = mid
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                out.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return out.toString()
    }

    /** Centre of a geohash cell. */
    fun decode(hash: String): LatLng {
        var latLo = -90.0
        var latHi = 90.0
        var lngLo = -180.0
        var lngHi = 180.0
        var even = true
        for (c in hash) {
            val v = BASE32.indexOf(c)
            for (b in 4 downTo 0) {
                val on = (v shr b) and 1 == 1
                if (even) {
                    val mid = (lngLo + lngHi) / 2
                    if (on) lngLo = mid else lngHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (on) latLo = mid else latHi = mid
                }
                even = !even
            }
        }
        return LatLng((latLo + latHi) / 2, (lngLo + lngHi) / 2)
    }

    /**
     * The cell containing [p] plus its 8 neighbours, which together cover every point within
     * one cell-width of [p]. Used for prefix queries (Firestore `geohash >= x && < x + "~"`).
     */
    fun coveringCells(p: LatLng, precision: Int): List<String> {
        val center = geohash(p, precision)
        val c = decode(center)
        // Cell size in degrees at this precision.
        val lngBits = (precision * 5 + 1) / 2
        val latBits = precision * 5 / 2
        val dLng = 360.0 / (1L shl lngBits)
        val dLat = 180.0 / (1L shl latBits)
        val cells = LinkedHashSet<String>()
        for (dy in -1..1) for (dx in -1..1) {
            val lat = (c.lat + dy * dLat).coerceIn(-89.999, 89.999)
            var lng = c.lng + dx * dLng
            if (lng > 180) lng -= 360
            if (lng < -180) lng += 360
            cells += geohash(LatLng(lat, lng), precision)
        }
        return cells.toList()
    }
}
