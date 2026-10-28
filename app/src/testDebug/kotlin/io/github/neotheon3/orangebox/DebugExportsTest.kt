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
import kotlinx.coroutines.launch
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

    @Test fun `a large history exports fast in batches with progress (#166)`() = runTest {
        // 40 trips × 900 samples ≈ the beta tester's 38 trips / 34k samples.
        val n = 40
        val perTrip = 900
        val big = InMemoryTrips(
            (1..n).map { TripEntity(id = it.toLong(), vehicleId = null, startedAtMillis = t0 + it * 3_600_000L, endedAtMillis = t0 + it * 3_600_000L + perTrip * 1000L, lastSampleAtMillis = t0 + it * 3_600_000L) },
            samplesByTrip = (1..n).associate { id ->
                id.toLong() to (0 until perTrip).map { i ->
                    TripSampleEntity(tripId = id.toLong(), timestampMillis = t0 + id * 3_600_000L + i * 1000L, speedKph = 50.0 + i % 40, rpm = 2000.0, fuelFlowLph = 3.1, coolantC = 90.0,
                        latitude = if (id % 10 == 0) null else 12.9 + i * 1e-5, longitude = if (id % 10 == 0) null else 77.5 + i * 1e-5, accuracyM = 5f)
                }
            },
            eventsByTrip = (1..n).associate { id -> id.toLong() to listOf(TripEventEntity(tripId = id.toLong(), timestampMillis = t0 + id * 3_600_000L + 5_000, type = "harsh_brake", value = -5.5, durationS = 2.0)) },
        )
        val progress = mutableListOf<DebugExports.Progress>()
        val started = System.nanoTime()
        val (json, gpx) = DebugExports(app, big).exportTrips { progress += it }
        val ms = (System.nanoTime() - started) / 1_000_000
        // Generous for slow CI hosts; it took ~2 minutes per file for this much data before #166.
        assertTrue("export took $ms ms", ms < 15_000)
        assertEquals(DebugExports.Progress(0, n), progress.first())
        assertEquals(DebugExports.Progress(n, n), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.done > a.done })
        // One samples query and one events query per batch, not per trip (and not again for the GPX).
        val batches = (n + DebugExports.BATCH - 1) / DebugExports.BATCH
        assertEquals(batches, big.samplesBatchCalls)
        assertEquals(batches, big.eventsBatchCalls)
        val trips = JSONObject(json.readText()).getJSONArray("trips")
        assertEquals(n, trips.length())
        assertEquals(perTrip, trips.getJSONObject(n - 1).getJSONArray("samples").length())
        assertEquals(2.0, trips.getJSONObject(0).getJSONArray("events").getJSONObject(0).getDouble("durationS"), 1e-9)
        val g = gpx.readText()
        assertEquals(n - n / 10, Regex("<trk>").findAll(g).count())
        assertEquals((n - n / 10) * perTrip, Regex("<trkpt ").findAll(g).count())
        assertTrue(g.trimEnd().endsWith("</gpx>"))
    }

    @Test fun `a cancelled export leaves no partial files`() = runTest {
        val before = exports.exportDir().listFiles().orEmpty().map { it.name }.toSet()
        val job = launch { exports.exportTrips { if (it.done > 0) throw kotlinx.coroutines.CancellationException("cancel") } }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, exports.exportDir().listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun `timestamps match SimpleDateFormat in UTC`() {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val r = java.util.Random(164)
        val cases = listOf(0L, 1L, 86_399_999L, 951_782_400_000L /* 2000-02-29 */, 4_102_444_799_999L, t0) + List(2_000) { r.nextLong() % 4_102_444_800_000L }.map { kotlin.math.abs(it) }
        cases.forEach { assertEquals("at $it", f.format(java.util.Date(it)), IsoUtc.format(it)) }
    }

    @Test fun `connection log export has a header and the buffered lines and the share intent grants read`() = runTest {
        ConnectionLog.enabled = true
        ConnectionLog.newSession("sim")
        ConnectionLog.pin("protocol", "ISO_15765_4_CAN_11_500")
        ConnectionLog.record("TR", "010C > 41 0C 1A F8 (38ms)")
        val f = exports.exportConnectionLog()
        val text = f.readText()
        assertTrue(text.startsWith("# Orange Box connection log"))
        assertTrue(text.contains(" TR 010C > 41 0C 1A F8 (38ms)\n"))
        assertTrue(text.contains("\n#   protocol: ISO_15765_4_CAN_11_500\n"))
        assertTrue(text.indexOf("# session 1 started") < text.indexOf(" TR 010C"))
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
    var samplesBatchCalls = 0
    var eventsBatchCalls = 0
    override suspend fun samplesOf(tripIds: List<Long>) = tripIds.associateWith { samples(it) }.also { samplesBatchCalls++ }
    override suspend fun eventsOf(tripIds: List<Long>) = tripIds.associateWith { events(it) }.also { eventsBatchCalls++ }
}
