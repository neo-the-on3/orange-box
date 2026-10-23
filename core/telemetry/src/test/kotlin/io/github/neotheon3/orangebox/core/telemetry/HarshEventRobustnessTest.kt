package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Event detection must count one sustained harsh acceleration/brake as one event even when the
 * sample stream is jittered, has stale (repeated) values, duplicates, out-of-order samples or gaps,
 * as happens under heavy load (Android Tester, Harsh script: 3/2 instead of 2/2).
 */
class HarshEventRobustnessTest {
    /** The Harsh script's shape: 0→60 km/h in 4 s, hold 10 s, 60→0 in 3 s, stop 5 s; twice. */
    private fun trueSpeed(tMs: Long): Double {
        val t = (tMs % 22_000) / 1000.0
        return when {
            t < 4 -> 15.0 * t
            t < 14 -> 60.0
            t < 17 -> 60.0 - 20.0 * (t - 14)
            else -> 0.0
        }
    }

    private fun sample(t: Long, speed: Double) =
        VehicleSample(t, mapOf(ObdPid.SPEED to speed.roundToInt().toDouble(), ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 30.0))

    private fun run(samples: List<VehicleSample>): TripTotals {
        val e = TelemetryEngine()
        var trip = TripTotals()
        samples.forEach { trip = e.onSample(it).trip }
        return trip
    }

    private val clean = (0L..44_000L step 500).map { sample(it, trueSpeed(it)) }

    @Test fun `clean 500 ms stream counts 2 and 2`() {
        val t = run(clean)
        assertEquals(2, t.harshAccelCount)
        assertEquals(2, t.harshBrakeCount)
    }

    @Test fun `stale repeated value mid-acceleration does not split the event`() {
        // Under load the poller reads the speed before the simulator advanced: same value, new timestamp.
        val s = clean.map { if (it.timestampMillis == 2_000L || it.timestampMillis == 24_000L) sample(it.timestampMillis, trueSpeed(it.timestampMillis - 500)) else it }
        val t = run(s)
        assertEquals(2, t.harshAccelCount)
        assertEquals(2, t.harshBrakeCount)
    }

    @Test fun `jittered timestamps do not split events`() {
        // Speed read at the true time, but timestamped up to ±200 ms off (emit time drifts with poll duration).
        val jitter = longArrayOf(0, 180, -150, 200, -200, 120, -90, 160)
        val s = (0L..44_000L step 500).mapIndexed { i, t -> sample(t + jitter[i % jitter.size], trueSpeed(t)) }
        val trip = run(s)
        assertEquals(2, trip.harshAccelCount)
        assertEquals(2, trip.harshBrakeCount)
    }

    @Test fun `duplicate and out-of-order samples are ignored`() {
        val s = clean.toMutableList()
        s.add(5, clean[4])                       // exact duplicate
        s.add(10, clean[7])                      // late, out-of-order sample
        s.add(40, sample(15_100, 0.0))           // out-of-order with a wild value
        val trip = run(s)
        assertEquals(2, trip.harshAccelCount)
        assertEquals(2, trip.harshBrakeCount)
        assertEquals(run(clean).distanceKm, trip.distanceKm, 1e-9)
    }

    @Test fun `delayed samples (gap) during a sustained event hold it`() {
        // No samples from 1.0 to 2.5 s and from 23.0 to 24.5 s.
        val s = clean.filterNot { it.timestampMillis in 1_001L..2_499L || it.timestampMillis in 23_001L..24_499L }
        val trip = run(s)
        assertEquals(2, trip.harshAccelCount)
        assertEquals(2, trip.harshBrakeCount)
    }

    @Test fun `overspeed hovering around the limit is one event`() {
        val e = TelemetryEngine()
        val speeds = listOf(118, 121, 123, 120, 122, 119, 121, 124, 122, 110, 100)
        var trip = TripTotals()
        speeds.forEachIndexed { i, v -> trip = e.onSample(VehicleSample(i * 1000L, mapOf(ObdPid.SPEED to v.toDouble()))).trip }
        assertEquals(1, trip.overspeedCount)
        // overspeed time runs from 121 km/h (t=1 s) until it drops below 117 (t=9 s)
        assertEquals(8.0, trip.overspeedS, 1e-9)
    }

    @Test fun `Harsh script under simulated load stays 2 and 2 for many random streams`() {
        val script = io.github.neotheon3.orangebox.core.simulator.DriveScript.HARSH
        repeat(50) { seed ->
            val rnd = java.util.Random(seed.toLong())
            val samples = mutableListOf<VehicleSample>()
            var t = 0L
            var lastSpeed = 0.0
            while (t <= (script.durationS * 1000).toLong()) {
                // poll interval 300–1500 ms; 20 % stale (speed from the previous poll); timestamp jitter ±150 ms;
                // 5 % duplicates and 5 % late re-deliveries of an older sample.
                val speed = if (rnd.nextInt(5) == 0) lastSpeed else script.frameAt(t / 1000.0).speedKph
                val s = sample(t + rnd.nextInt(301) - 150, speed)
                samples += s
                if (rnd.nextInt(20) == 0) samples += s
                if (rnd.nextInt(20) == 0 && samples.size > 3) samples += samples[samples.size - 3]
                lastSpeed = speed
                t += 300 + rnd.nextInt(1201)
            }
            val trip = run(samples)
            assertEquals("seed $seed accel", 2, trip.harshAccelCount)
            assertEquals("seed $seed brake", 2, trip.harshBrakeCount)
        }
    }

    @Test fun `event start and end are logged without PII`() {
        val lines = mutableListOf<String>()
        io.github.neotheon3.orangebox.core.common.OrangeLog.sink =
            io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, tag, msg, _ -> lines += "$tag $msg" }
        try {
            run(clean)
        } finally {
            io.github.neotheon3.orangebox.core.common.OrangeLog.sink = io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, _, _, _ -> }
        }
        val events = lines.filter { it.startsWith("OrangeBox/Telemetry harsh_") }
        assertEquals(events.toString(), 8, events.size)
        assertEquals(2, events.count { it.contains("harsh_accel_start") })
        assertEquals(2, events.count { it.contains("harsh_brake_end") })
        org.junit.Assert.assertTrue(events.all { Regex("^OrangeBox/Telemetry harsh_(accel|brake)_(start|end) [a-z_]+=").containsMatchIn(it) })
    }
}
