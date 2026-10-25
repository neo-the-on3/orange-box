package io.github.neotheon3.orangebox.core.driving.crash

import io.github.neotheon3.orangebox.core.model.AppPreferences
import io.github.neotheon3.orangebox.core.model.CrashPreferences
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashMonitorTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))

    private class Rig(
        val monitor: CrashMonitor,
        val conn: FakeObdConnection,
        val telemetry: FakeTelemetrySource,
        val prefs: FakeAppPreferencesRepository,
        val motion: Channel<MotionSample>,
    )

    private fun TestScope.rig(crash: CrashPreferences = CrashPreferences(countdownSeconds = 10)): Rig {
        val conn = FakeObdConnection()
        val telemetry = FakeTelemetrySource()
        val prefs = FakeAppPreferencesRepository(AppPreferences(crash = crash))
        val channel = Channel<MotionSample>(Channel.UNLIMITED)
        val monitor = CrashMonitor(backgroundScope, conn, telemetry, prefs, { channel.receiveAsFlow() }, { testScheduler.currentTime })
        monitor.install()
        runCurrent()
        return Rig(monitor, conn, telemetry, prefs, channel)
    }

    private val engine = TelemetryEngine()

    private fun TestScope.moving(r: Rig, kph: Double) {
        r.telemetry.latest.value = engine.onSample(VehicleSample(testScheduler.currentTime, mapOf(ObdPid.SPEED to kph, ObdPid.RPM to 2000.0)))
        runCurrent()
    }

    /** A 9 g, 120 ms pulse followed by enough quiet samples to confirm it. */
    private fun TestScope.crash(r: Rig, t0: Long = 10_000) {
        (0 until 6).forEach { i -> r.motion.trySend(MotionSample.ofG(t0 + i * 20, if (i in 1..4) 8.0 else 3.0)) }
        (6 until 30).forEach { i -> r.motion.trySend(MotionSample.ofG(t0 + i * 20, 1.0)) }
        runCurrent()
    }

    @Test fun `motion is read only while driving with detection on`() = runTest {
        val r = rig()
        assertFalse(r.monitor.watching.value)
        r.conn.state.value = connected; runCurrent()
        assertTrue(r.monitor.watching.value)
        r.prefs.setCrash(CrashPreferences(detectionEnabled = false)); runCurrent()
        assertFalse(r.monitor.watching.value)
        r.prefs.setCrash(CrashPreferences()); runCurrent()
        assertTrue(r.monitor.watching.value)
        // The link drops (a collision can cut the dongle's power): keep watching for a minute.
        r.conn.state.value = ObdConnectionState.Idle; runCurrent()
        assertTrue(r.monitor.watching.value)
        advanceTimeBy(CrashMonitor.AFTER_DRIVE_MILLIS + 1); runCurrent()
        assertFalse(r.monitor.watching.value)
    }

    @Test fun `impact while moving shows the countdown, which times out`() = runTest {
        val r = rig()
        r.conn.state.value = connected; runCurrent()
        moving(r, 60.0)
        crash(r)
        val p = r.monitor.prompt.value!!
        assertEquals(CrashTrigger.IMPACT, p.trigger)
        assertEquals(10, p.remainingSeconds)
        assertTrue(p.counting)
        advanceTimeBy(3_001); runCurrent()
        assertEquals(7, r.monitor.prompt.value!!.remainingSeconds)
        advanceTimeBy(7_000); runCurrent()
        val done = r.monitor.prompt.value!!
        assertTrue(done.timedOut)
        assertFalse(done.counting)
        r.monitor.completed(done.id)
        assertNull(r.monitor.prompt.value)
    }

    @Test fun `cancel dismisses the countdown`() = runTest {
        val r = rig()
        r.conn.state.value = connected; runCurrent()
        moving(r, 40.0)
        crash(r)
        advanceTimeBy(2_000); runCurrent()
        r.monitor.cancel()
        assertNull(r.monitor.prompt.value)
        advanceTimeBy(20_000); runCurrent()
        assertNull(r.monitor.prompt.value)
    }

    @Test fun `an impact in a parked car is ignored`() = runTest {
        val r = rig()
        r.conn.state.value = connected; runCurrent()
        moving(r, 0.0)
        crash(r)
        assertNull(r.monitor.prompt.value)
    }

    @Test fun `the car stopped moving long ago, so the impact is ignored`() = runTest {
        val r = rig()
        r.conn.state.value = connected; runCurrent()
        moving(r, 50.0)
        advanceTimeBy(CrashMonitor.RECENT_MOVING_MILLIS + 1_000)
        moving(r, 0.0)
        crash(r)
        assertNull(r.monitor.prompt.value)
    }

    @Test fun `simulated impact uses the countdown from Settings, without sensor or speed`() = runTest {
        val r = rig(CrashPreferences(countdownSeconds = 15))
        r.monitor.injectImpact(); runCurrent()
        val p = r.monitor.prompt.value!!
        assertEquals(CrashTrigger.SIMULATED, p.trigger)
        assertEquals(15, p.countdownSeconds)
        assertEquals(CrashMonitor.SIMULATED_PEAK_G, p.peakG!!, 0.0)
    }

    @Test fun `manual prompt has no countdown, an impact replaces it`() = runTest {
        val r = rig()
        r.monitor.showManual()
        val manual = r.monitor.prompt.value!!
        assertEquals(CrashTrigger.MANUAL, manual.trigger)
        assertNull(manual.remainingSeconds)
        assertFalse(manual.counting)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(manual, r.monitor.prompt.value)
        r.monitor.injectImpact(); runCurrent()
        assertEquals(CrashTrigger.SIMULATED, r.monitor.prompt.value!!.trigger)
    }

    @Test fun `a second impact does not restart a running countdown`() = runTest {
        val r = rig()
        r.monitor.injectImpact(); runCurrent()
        advanceTimeBy(4_001); runCurrent()
        val first = r.monitor.prompt.value!!
        r.monitor.injectImpact(); runCurrent()
        assertEquals(first.id, r.monitor.prompt.value!!.id)
        r.monitor.showManual()
        assertEquals(first.id, r.monitor.prompt.value!!.id)
    }

    @Test fun `completed for a stale prompt id is ignored`() = runTest {
        val r = rig()
        r.monitor.injectImpact(); runCurrent()
        val id = r.monitor.prompt.value!!.id
        r.monitor.completed(id + 1)
        assertEquals(id, r.monitor.prompt.value!!.id)
    }
}
