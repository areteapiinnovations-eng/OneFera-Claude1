package com.onefera.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearTest {
    @Test
    fun `geohash matches the reference encoding`() {
        assertEquals("u4pruydqqvj", Geo.geohash(LatLng(57.64911, 10.40744), 11))
        assertEquals("te7u6r", Geo.geohash(LatLng(19.07, 72.88), 6))
        val centre = Geo.decode("te7u6r")
        assertEquals("te7u6r", Geo.geohash(centre, 6))
    }

    @Test
    fun `covering cells include the centre and its neighbours`() {
        val cells = Geo.coveringCells(LatLng(19.07, 72.88), 5)
        assertEquals(9, cells.size)
        assertTrue(Geo.geohash(LatLng(19.07, 72.88), 5) in cells)
        // A point ~3 km away is inside one of the cells.
        assertTrue(Geo.geohash(LatLng(19.095, 72.885), 5) in cells)
    }

    @Test
    fun `coarse rounding and distances`() {
        assertEquals(LatLng(19.08, 72.88), Geo.coarse(LatLng(19.07612, 72.87765)))
        val km = Geo.distanceKm(LatLng(19.076, 72.8777), LatLng(18.5204, 73.8567)) // Mumbai → Pune
        assertTrue(km in 115.0..125.0)
        assertEquals("<1 km away", Geo.label(0.4))
        assertEquals("~3 km away", Geo.label(3.2))
        assertTrue(Geo.bearing(LatLng(0.0, 0.0), LatLng(1.0, 0.0)) < 1.0) // due north
    }

    @Test
    fun `every radius's cells cover the whole circle`() {
        // Hyderabad and Delhi latitudes; sample points on the radius edge in 16 directions.
        for (centre in listOf(LatLng(17.39, 78.49), LatLng(28.61, 77.21))) {
            for (radius in NearRadius.entries) {
                val cells = Geo.coveringCells(centre, radius.precision)
                for (step in 0 until 16) {
                    val angle = Math.toRadians(step * 22.5)
                    val northKm = radius.km * kotlin.math.cos(angle)
                    val eastKm = radius.km * kotlin.math.sin(angle)
                    val edge = LatLng(
                        centre.lat + northKm / 110.574,
                        centre.lng + eastKm / (111.320 * kotlin.math.cos(Math.toRadians(centre.lat))),
                    )
                    assertTrue("$radius misses $edge", Geo.geohash(edge, radius.precision) in cells)
                }
            }
        }
    }

    @Test
    fun `two phones side by side land in each other's search`() {
        val a = Geo.coarse(LatLng(17.38512, 78.48674))
        val b = Geo.coarse(LatLng(17.38549, 78.48701))
        assertTrue(Geo.distanceKm(a, b) <= NearRadius.Close.km)
        assertTrue(Geo.geohash(b, 6).startsWith(Geo.geohash(b, NearRadius.Close.precision)))
        assertTrue(Geo.geohash(b, NearRadius.Close.precision) in Geo.coveringCells(a, NearRadius.Close.precision))
    }
}
