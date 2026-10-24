package io.github.neotheon3.orangebox.feature.trips.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMercatorTest {
    @Test fun `known slippy-map tiles`() {
        // OSM wiki examples: London at z10, Bengaluru at z12.
        assertEquals(511 to 340, WebMercator.tileOf(GeoPoint(51.5074, -0.1278), 10))
        assertEquals(2930 to 1899, WebMercator.tileOf(GeoPoint(12.9716, 77.5946), 12))
        assertEquals(0 to 0, WebMercator.tileOf(GeoPoint(85.0, -180.0), 3))
    }

    @Test fun `projection is centred and clamps the poles`() {
        val c = WebMercator.project(GeoPoint(0.0, 0.0))
        assertEquals(0.5, c.x, 1e-12)
        assertEquals(0.5, c.y, 1e-12)
        assertEquals(0.0, WebMercator.project(GeoPoint(90.0, 0.0)).y, 1e-9)
        assertEquals(1.0, WebMercator.project(GeoPoint(-90.0, 0.0)).y, 1e-9)
    }

    @Test fun `bounds grow to a minimum span around a single point`() {
        val p = WebMercator.project(GeoPoint(12.97, 77.59))
        val b = MapBounds.of(listOf(p))!!.withMinimumSize(1e-4)
        assertEquals(1e-4, b.width, 1e-12)
        assertTrue(p.x in b.left..b.right && p.y in b.top..b.bottom)
    }
}
