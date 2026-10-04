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
}
