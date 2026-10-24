package io.github.neotheon3.orangebox

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.common.ConnectionLog
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DebugExportsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val t0 = 1_761_300_000_000L
    private val repo = InMemoryTrips(
        listOf(
            TripEntity(id = 1, vehicleId = null, startedAtMillis = t0, endedAtMillis = t0 + 3_000, lastSampleAtMillis = t0 + 2_000, endReason = "engine_off", distanceKm = 0.05, harshBrakeCount = 1),
            TripEntity(id = 2, vehicleId = 3, startedAtMillis = t0 + 10_000, lastSampleAtMillis = t0 + 10_000),
        ),
        samplesByTrip = mutableMapOf(
            1L to (0..2).map { TripSampleEntity(tripId = 1, timestampMillis = t0 + it * 1000L, speedKph = 30.0 + it, rpm = 1500.0, fuelFlowLph = null, coolantC = 85.0, latitude = 12.97 + it * 1e-4, longitude = 77.59, accuracyM = 5f) },
            2L to listOf(TripSampleEntity(tripId = 2, timestampMillis = t0 + 10_000, speedKph = 0.0, rpm = 800.0, fuelFlowLph = 0.6, coolantC = 70.0)),
        ),
        eventsByTrip = mutableMapOf(1L to listOf(TripEventEntity(tripId = 1, timestampMillis = t0 + 1_000, type = "harsh_brake", value = -3.9))),
    )
    private val exports = DebugExports(app, repo)

    @After fun off() { ConnectionLog.enabled = false; ConnectionLog.clear() }

    @Test fun `trips export as JSON with samples and events, and GPX with located tracks only`() = runTest {
        val (json, gpx) = exports.exportTrips()
        assertTrue(json.path.contains("Download/orange-box-exports"))
        val root = JSONObject(json.readText())
        assertEquals("orange-box-trips/1", root.getString("format"))
        val trips = root.getJSONArray("trips")
        assertEquals(2, trips.length())
        val first = trips.getJSONObject(0)
        assertEquals(3, first.getJSONArray("samples").length())
        assertEquals(12.9701, first.getJSONArray("samples").getJSONArray(1).getDouble(5), 1e-9)
        assertEquals("harsh_brake", first.getJSONArray("events").getJSONObject(0).getString("type"))
        assertEquals(1, first.getJSONObject("summary").getInt("harshBrakeCount"))
        assertTrue(trips.getJSONObject(1).isNull("endedAt"))
        assertTrue(trips.getJSONObject(1).getJSONArray("samples").getJSONArray(0).isNull(5))
        val g = gpx.readText()
        assertEquals(3, Regex("<trkpt ").findAll(g).count())
        assertEquals(1, Regex("<trk>").findAll(g).count())
    }

    @Test fun `connection log export has a header and the buffered lines and the share intent grants read`() = runTest {
        ConnectionLog.enabled = true
        ConnectionLog.record("TX", "010C")
        ConnectionLog.record("RX", "41 0C 1A F8 (38ms)")
        val f = exports.exportConnectionLog()
        val text = f.readText()
        assertTrue(text.startsWith("# Orange Box connection log"))
        assertTrue(text.contains(" TX 010C\n"))
        assertTrue(text.contains(" RX 41 0C 1A F8 (38ms)\n"))
        val chooser = exports.shareIntent(listOf(f), "log")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

private class InMemoryTrips(
    list: List<TripEntity>,
    private val samplesByTrip: Map<Long, List<TripSampleEntity>>,
    private val eventsByTrip: Map<Long, List<TripEventEntity>>,
) : TripRepository {
    override val trips = MutableStateFlow(list)
    override suspend fun start(trip: TripEntity) = error("read-only")
    override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) = error("read-only")
    override suspend fun closeOrphans() = 0
    override suspend fun trip(id: Long) = trips.value.firstOrNull { it.id == id }
    override suspend fun samples(tripId: Long) = samplesByTrip[tripId].orEmpty()
    override suspend fun events(tripId: Long) = eventsByTrip[tripId].orEmpty()
}
