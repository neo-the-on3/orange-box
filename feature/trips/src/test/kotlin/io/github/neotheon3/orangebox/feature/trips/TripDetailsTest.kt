package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.feature.trips.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TripDetailsTest {
    private val start = LocalDateTime.of(2026, 10, 21, 9, 10)
    private val t = trip(4, start, minutes = 60, km = 45.0, litres = 3.0, harshAccel = 1, harshBrake = 2, overspeed = 1)
        .copy(maxSpeedKph = 128.0, co2Grams = 6_960.0, idleS = 120.0, ecoS = 1_800.0)

    private fun sample(sec: Long, lat: Double?, lon: Double?, acc: Float? = 5f, speed: Double? = 50.0) =
        TripSampleEntity(tripId = 4, timestampMillis = t.startedAtMillis + sec * 1000, speedKph = speed, rpm = 2000.0, fuelFlowLph = 3.0, coolantC = 90.0, latitude = lat, longitude = lon, accuracyM = acc)

    @Test fun `metrics derive averages and convert units`() {
        val m = TripDetails.metrics(t)
        assertEquals(45.0, m.avgSpeedKph!!, 1e-9)
        assertEquals(15.0, m.economyKmPerL!!, 1e-9)
        assertEquals(6.96, m.co2Kg, 1e-9)
        assertEquals(3, m.harshAccel + m.harshBrake)
        assertNull(TripDetails.metrics(t.copy(durationS = 0.0)).avgSpeedKph)
    }

    @Test fun `route keeps located, accurate, plausible fixes in time order`() {
        val samples = listOf(
            sample(2, 12.9720, 77.5950),
            sample(0, 12.9716, 77.5946),
            sample(1, null, null),
            sample(3, 12.9800, 77.6000, acc = 300f),
            sample(4, 0.0, 0.0),
            sample(5, 12.9730, 77.5960, acc = null),
        )
        val d = TripDetails.detail(t, samples, emptyList(), TEST_ZONE)
        assertEquals(listOf(GeoPoint(12.9716, 77.5946), GeoPoint(12.9720, 77.5950), GeoPoint(12.9730, 77.5960)), d.route)
        assertTrue(d.hasRoute)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), d.speedSeries.map { it.first })
    }

    @Test fun `a trip without location has no route but keeps its speed series`() {
        val d = TripDetails.detail(t, (0L..9L).map { sample(it, null, null) }, emptyList(), TEST_ZONE)
        assertFalse(d.hasRoute)
        assertEquals(10, d.speedSeries.size)
        val one = TripDetails.detail(t, listOf(sample(0, 12.97, 77.59)), emptyList(), TEST_ZONE)
        assertFalse("one fix is not a route", one.hasRoute)
    }

    @Test fun `events are typed, ordered and keep their position, unknown types are skipped`() {
        val events = listOf(
            TripEventEntity(tripId = 4, timestampMillis = t.startedAtMillis + 60_000, type = "harsh_brake", value = -4.1, latitude = 12.97, longitude = 77.59),
            TripEventEntity(tripId = 4, timestampMillis = t.startedAtMillis + 30_000, type = "overspeed", value = 121.0),
            TripEventEntity(tripId = 4, timestampMillis = t.startedAtMillis + 10_000, type = "something_new"),
        )
        val d = TripDetails.detail(t, emptyList(), events, TEST_ZONE)
        assertEquals(listOf(TripEventType.OVERSPEED, TripEventType.HARSH_BRAKE), d.events.map { it.type })
        assertNull(d.events[0].position)
        assertEquals(GeoPoint(12.97, 77.59), d.events[1].position)
        assertEquals(start.plusSeconds(60), d.events[1].time)
    }
}
