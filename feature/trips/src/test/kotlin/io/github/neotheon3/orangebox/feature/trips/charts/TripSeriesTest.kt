package io.github.neotheon3.orangebox.feature.trips.charts

import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSeriesTest {
    private fun s(sec: Long, speed: Double?, flow: Double? = 3.6) =
        TripSampleEntity(tripId = 1, timestampMillis = 1_000_000 + sec * 1000, speedKph = speed, rpm = 2000.0, fuelFlowLph = flow, coolantC = 90.0)

    @Test fun `zones use the telemetry bounds, upper bound inclusive`() {
        assertEquals(0, TripSeries.zoneOf(0.0))
        assertEquals(0, TripSeries.zoneOf(0.9))
        assertEquals(1, TripSeries.zoneOf(1.0))
        assertEquals(1, TripSeries.zoneOf(30.0))
        assertEquals(2, TripSeries.zoneOf(30.1))
        assertEquals(4, TripSeries.zoneOf(120.0))
        assertEquals(5, TripSeries.zoneOf(120.1))
    }

    @Test fun `each sample's speed holds until the next one, gaps count at most 5 s`() {
        // 10 s stopped, 20 s at 50, 5 s at 130, a 60 s gap at 80 (counts 5 s), then the last sample (no interval).
        val samples = (0 until 10).map { s(it.toLong(), 0.0) } + (10 until 30).map { s(it.toLong(), 50.0) } +
            (30 until 35).map { s(it.toLong(), 130.0) } + listOf(s(35, 80.0), s(95, 80.0))
        val z = TripSeries.speedZones(samples.shuffled(java.util.Random(45)))
        assertEquals(listOf(10.0, 0.0, 20.0, 5.0, 0.0, 5.0), z.seconds)
        assertEquals(40.0, z.totalS, 1e-9)
        assertEquals(0.5, z.shares[2], 1e-9)
        assertEquals(1.0, z.shares.sum(), 1e-9)
    }

    @Test fun `a trip with no samples has empty zones`() {
        val z = TripSeries.speedZones(emptyList())
        assertEquals(6, z.seconds.size)
        assertTrue(z.shares.all { it == 0.0 })
    }

    @Test fun `economy is km per litre per minute and skips idle minutes`() {
        // Minute 0: 72 km/h on 3.6 L/h = 20 km/L. Minute 1: idling (0 km/h). Minute 2: 36 km/h on 7.2 L/h = 5 km/L.
        val samples = (0..59).map { s(it.toLong(), 72.0, 3.6) } + (60..119).map { s(it.toLong(), 0.0, 0.8) } + (120..180).map { s(it.toLong(), 36.0, 7.2) }
        val e = TripSeries.economy(samples, 1_000_000)
        assertEquals(listOf(30.0, 150.0), e.map { it.t })
        assertEquals(20.0, e[0].value, 1e-6)
        assertEquals(5.0, e[1].value, 1e-6)
    }

    @Test fun `economy without fuel flow is empty`() {
        assertTrue(TripSeries.economy((0..200).map { s(it.toLong(), 50.0, null) }, 1_000_000).isEmpty())
    }

    @Test fun `axis maximum rounds up to 1, 2, 2,5 or 5 times a power of ten`() {
        assertEquals(100.0, niceMax(92.0), 1e-9)
        assertEquals(25.0, niceMax(21.3), 1e-9)
        assertEquals(50.0, niceMax(50.0), 1e-9)
        assertEquals(200.0, niceMax(121.0), 1e-9)
        assertEquals(1.0, niceMax(0.0), 1e-9)
    }
}
