package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A long poll stall (> maxGapMillis) ends an open event as `signal_lost` at the last sample before the gap,
 * so its logged duration matches the trip's event time, which never integrates across the gap (M4 retest:
 * Highway logged overspeed_end duration_s=57.07 against a 40.6 s readout after a ~16.5 s stall).
 */
class SignalLostEventTest {
    private val lines = mutableListOf<String>()

    @Before fun captureLog() {
        OrangeLog.sink = OrangeLog.Sink { _, tag, msg, _ -> lines += "$tag $msg" }
    }

    @After fun releaseLog() {
        OrangeLog.sink = OrangeLog.Sink { _, _, _, _ -> }
    }

    private fun TelemetryEngine.at(ms: Long, kph: Double) =
        onSample(VehicleSample(ms, mapOf(ObdPid.SPEED to kph, ObdPid.RPM to 3_500.0)))

    private fun ends(name: String) = lines.filter { it.startsWith("OrangeBox/Telemetry ${name}_end ") }

    private fun String.field(key: String) = Regex(" $key=([^ ]+)").find(this)!!.groupValues[1]

    @Test fun `overspeed cut by a stall closes at the last sample and matches the readout`() {
        val e = TelemetryEngine()
        for (s in 0..40) e.at(1_000L * s, 130.0) // starts at t=0, last pre-stall sample at 40 s
        val after = e.at(56_500, 110.0) // 16.5 s stall; speed has dropped by the time polling resumes
        assertFalse(after.instant.overspeed)
        val end = ends("overspeed").single()
        assertEquals("signal_lost", end.field("reason"))
        assertEquals("40.00", end.field("duration_s"))
        assertEquals(40.0, after.trip.overspeedS, 1e-9)
        assertEquals(1, after.trip.overspeedCount)
    }

    @Test fun `still over the limit after the stall counts a second event`() {
        val e = TelemetryEngine()
        for (s in 0..10) e.at(1_000L * s, 130.0)
        val after = e.at(20_000, 130.0)
        assertEquals("10.00", ends("overspeed").single().field("duration_s"))
        assertEquals(2, after.trip.overspeedCount)
        assertEquals(11.0, e.at(21_000, 130.0).trip.overspeedS, 1e-9) // 10 s before the stall + 1 s after
    }

    @Test fun `harsh event cut by a stall closes at the last sample too`() {
        val e = TelemetryEngine()
        // 0 → 72 km/h at 5 m/s² (harsh accel), sampled every 500 ms, then a 10 s stall.
        var last = 0L
        for (i in 0..8) { last = 500L * i; e.at(last, 9.0 * i) }
        e.at(last + 10_000, 72.0)
        val end = ends("harsh_accel").single()
        assertEquals("signal_lost", end.field("reason"))
        val start = lines.first { it.startsWith("OrangeBox/Telemetry harsh_accel_start ") }.field("t_ms").toLong()
        assertEquals(String.format(java.util.Locale.US, "%.2f", (last - start) / 1000.0), end.field("duration_s"))
    }
}
