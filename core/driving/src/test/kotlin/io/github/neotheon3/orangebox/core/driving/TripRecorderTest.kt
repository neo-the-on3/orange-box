package io.github.neotheon3.orangebox.core.driving

import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {
    /** Real [TelemetryEngine] behind the [TelemetrySource] contract, fed by hand. */
    private class EngineSource : TelemetrySource {
        val engine = TelemetryEngine()
        override val latest = MutableStateFlow<TelemetrySnapshot?>(null)
        private var resetPending = false
        override fun resetTrip() { resetPending = true }
        fun feed(sample: VehicleSample) {
            if (resetPending) { engine.resetTrip(); resetPending = false }
            latest.value = engine.onSample(sample)
        }
        fun lost() { latest.value = null; engine.reset() }
    }

    private class MemoryTrips : TripRepository {
        val rows = linkedMapOf<Long, TripEntity>()
        val samples = mutableListOf<TripSampleEntity>()
        val events = mutableListOf<TripEventEntity>()
        var checkpoints = 0
        override val trips = MutableStateFlow<List<TripEntity>>(emptyList())
        override suspend fun start(trip: TripEntity) = trip.copy(id = rows.size + 1L).also { rows[it.id] = it }
        override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) {
            rows[trip.id] = trip; this.samples += samples; this.events += events; checkpoints++
        }
        var closeOrphansCalls = 0
        override suspend fun closeOrphans(): Int { closeOrphansCalls++; return 0 }
        override suspend fun trip(id: Long) = rows[id]
        override suspend fun samples(tripId: Long) = samples.filter { it.tripId == tripId }
        override suspend fun events(tripId: Long) = events.filter { it.tripId == tripId }
    }

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))

    private class Rig(val scope: TestScope, val recorder: TripRecorder, val source: EngineSource, val trips: MemoryTrips, val conn: FakeObdConnection)

    private fun TestScope.rig(location: LocationSource = LocationSource { emptyFlow() }): Rig {
        val source = EngineSource()
        val trips = MemoryTrips()
        val conn = FakeObdConnection(connected)
        val recorder = TripRecorder(backgroundScope, conn, source, trips, location, { testScheduler.currentTime }, { flowOf(7L) })
        recorder.start()
        runCurrent()
        return Rig(this, recorder, source, trips, conn)
    }

    /** One sample per second of sample time for [seconds], advancing the test clock with it. */
    private fun Rig.drive(seconds: Int, speed: Double, rpm: Double, maf: Double = 10.0) = repeat(seconds) {
        scope.advanceTimeBy(1_000)
        source.feed(
            VehicleSample(
                scope.testScheduler.currentTime,
                mapOf(ObdPid.SPEED to speed, ObdPid.RPM to rpm, ObdPid.MAF to if (rpm > 0) maf else 0.0, ObdPid.COOLANT_TEMP to 85.0, ObdPid.THROTTLE to 20.0),
            ),
        )
        scope.runCurrent()
    }

    @Test fun `scripted drive records the expected trip (golden)`() = runTest {
        val r = rig(location = LocationSource { flowOf(GeoFix(12.97, 77.59, 5f, 0)) })
        r.drive(3, speed = 0.0, rpm = 0.0) // connected, engine off: no trip
        assertTrue(r.trips.rows.isEmpty())
        r.drive(1, speed = 72.0, rpm = 2000.0) // engine on: trip starts, totals reset
        assertEquals(7L, r.trips.rows.values.single().vehicleId)
        r.drive(100, speed = 72.0, rpm = 2000.0) // 100 s at 72 km/h = 2.000 km
        r.drive(10, speed = 130.0, rpm = 3200.0) // 10 s at 130 km/h = 0.361 km, one overspeed
        r.drive(5, speed = 0.0, rpm = 800.0) // stopped, idling
        r.drive(31, speed = 0.0, rpm = 0.0) // engine off for longer than the 30 s grace

        val trip = r.trips.rows.values.single()
        assertEquals(TripRecorder.END_ENGINE_OFF, trip.endReason)
        // Trapezoids over 1 s samples: 100 × 72 km/h + (72→130) + 9 × 130 + (130→0) = 2.3711 km.
        assertEquals(2.3711, trip.distanceKm, 0.0005)
        assertEquals(130.0, trip.maxSpeedKph, 1e-9)
        assertEquals(1, trip.overspeedCount)
        // MAF 10 g/s petrol ≈ 3.30 L/h for the 115 s with the engine running; engine off burns nothing.
        assertEquals(0.1055, trip.fuelLitres, 0.0005)
        assertTrue("duration ${trip.durationS}", trip.durationS in 140.0..148.0)
        assertTrue("idle ${trip.idleS}", trip.idleS >= 5.0)
        assertEquals(trip.lastSampleAtMillis, trip.endedAtMillis)
        val samples = r.trips.samples
        assertTrue("one sample a second: ${samples.size}", samples.size in 140..148)
        assertTrue(samples.all { it.latitude == 12.97 && it.longitude == 77.59 })
        // 0→72 and 72→130 in one second are harsh, as is 130→0.
        assertEquals(
            listOf(TripRecorder.EVENT_HARSH_ACCEL, TripRecorder.EVENT_HARSH_ACCEL, TripRecorder.EVENT_OVERSPEED, TripRecorder.EVENT_HARSH_BRAKE),
            r.trips.events.map { it.type },
        )
        assertEquals(1, trip.harshBrakeCount)
        assertNull(r.recorder.active.value)
    }

    @Test fun `summary is checkpointed every 5 s while driving`() = runTest {
        val r = rig()
        r.drive(1, 50.0, 1500.0)
        r.drive(12, 50.0, 1500.0)
        val open = r.trips.rows.values.single()
        assertNull(open.endedAtMillis)
        assertTrue("checkpoints ${r.trips.checkpoints}", r.trips.checkpoints >= 2)
        assertEquals(50.0 * 10 / 3600, open.distanceKm, 0.01)
        assertEquals(open.id, r.recorder.active.value?.id)
    }

    @Test fun `a reconnect inside the grace keeps one trip and carries the totals`() = runTest {
        val r = rig()
        r.drive(1, 72.0, 2000.0)
        r.drive(50, 72.0, 2000.0) // 1.0 km
        r.conn.state.value = ObdConnectionState.Idle
        r.source.lost()
        r.scope.advanceTimeBy(10_000); r.scope.runCurrent()
        r.conn.state.value = connected
        r.drive(50, 72.0, 2000.0) // +1.0 km (the engine restarted its totals)
        val trip = r.trips.rows.values.single()
        assertNull(trip.endedAtMillis)
        assertEquals(2.0, r.recorder.active.value!!.totals.distanceKm, 0.03)
        // #144: the engine's own totals restarted at the reconnect; the recorder's (dashboard + notification) did not.
        val engineS = r.source.latest.value!!.trip.durationS
        val recordedS = r.recorder.active.value!!.totals.durationS
        assertTrue("engine $engineS s only counts since the reconnect", engineS < 52.0)
        assertTrue("recorded $recordedS s carries the whole trip", recordedS > 95.0)
    }

    @Test fun `link lost for longer than the grace ends the trip as disconnected`() = runTest {
        val r = rig()
        r.drive(10, 72.0, 2000.0)
        r.conn.state.value = ObdConnectionState.Idle
        r.source.lost()
        r.scope.advanceTimeBy(31_000); r.scope.runCurrent()
        assertEquals(TripRecorder.END_DISCONNECTED, r.trips.rows.values.single().endReason)
    }

    @Test fun `orphans are closed once, at the first trip, and starting alone never touches storage`() = runTest {
        val r = rig()
        r.drive(3, 0.0, 0.0)
        assertEquals(0, r.trips.closeOrphansCalls)
        r.drive(2, 50.0, 1500.0)
        r.drive(31, 0.0, 0.0) // ends
        r.drive(2, 50.0, 1500.0) // second trip
        assertEquals(1, r.trips.closeOrphansCalls)
        assertEquals(2, r.trips.rows.size)
    }

    @Test fun `stop ends the trip at once`() = runTest {
        val r = rig()
        r.drive(10, 72.0, 2000.0)
        r.recorder.stop(TripRecorder.END_USER)
        val trip = r.trips.rows.values.single()
        assertEquals(TripRecorder.END_USER, trip.endReason)
        assertEquals(0.18, trip.distanceKm, 0.03)
        r.drive(5, 72.0, 2000.0) // stopped: no new trip
        assertEquals(1, r.trips.rows.size)
    }
}
