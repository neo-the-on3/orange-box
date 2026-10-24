package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.pid.PollingConfig
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.simulator.DriveScript
import io.github.neotheon3.orangebox.core.simulator.DriveScriptPlayer
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Regression for #120: on an API 25 ARM emulator City eco came out at 104.7–106.9 s (golden 91 s), Harsh
 * eco at 20.3 s (19 s) and Highway at 2 s (0 s), with overspeed lasting 50.05 s (46.5 s). Every number is
 * the golden stretched by ≈ 7–9 %: the debug [DriveScriptPlayer] advanced script time by a fixed tick per
 * loop, so overrunning `delay`s slowed the drive down in wall time and lowered every acceleration by the
 * same factor (City's 1.74 m/s² stops fell below the 1.5 m/s² eco limit).
 *
 * Here the whole debug path runs on a "slow device": the wall clock runs [LAG] faster than the coroutine
 * delays (each tick overruns by 8 %), the app polls at jittery 0.5–1.1 s intervals, and samples are
 * stamped with the wall clock as [io.github.neotheon3.orangebox.core.obd.pid.PidPoller] does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlowDevicePlaybackTest {
    private data class Result(val trip: TripTotals, val wallS: Double, val ecoSamples: Int, val brakePeaks: List<Double> = emptyList())

    private suspend fun TestScope.play(
        script: DriveScript, seed: Int, coastS: Int = 0,
        /** Player refresh period; a slow device overruns it (the #142 runs effectively had ~250 ms). */
        playerTickMillis: Long = 100,
        /** Time per PID read, as on a real link: later PIDs of a tick are read after the SPEED timestamp. */
        perPidMillis: Long = 0,
    ): Result {
        val wall = { (testScheduler.currentTime * LAG).toLong() }
        val controller = SimulatorController()
        val transport = SimulatedElm327Transport(controller).also { it.open() }
        val session = Elm327Session(transport).also { it.initialize() }
        val pids = session.readSupportedPids().filter(PollingConfig().fast + PollingConfig().slow)
        val engine = TelemetryEngine()
        val player = DriveScriptPlayer(controller, backgroundScope, tickMillis = playerTickMillis, clock = wall)
        val rnd = Random(seed)
        player.start(script)
        runCurrent()
        val startWall = wall()
        var trip = TripTotals()
        var settledAt: Long? = null
        var ecoSamples = 0
        val brakePeaks = mutableListOf<Double>()
        var peak = 0.0
        while (true) {
            val stamp = wall()
            val values = LinkedHashMap<io.github.neotheon3.orangebox.core.obd.pid.ObdPid, Double>()
            for (p in pids) {
                session.read(p)?.let { values[p] = it }
                if (perPidMillis > 0) { advanceTimeBy(perPidMillis); runCurrent() }
            }
            val snap = engine.onSample(VehicleSample(stamp, values))
            if (snap.instant.eco) ecoSamples++
            if (snap.instant.harshBrake) peak = maxOf(peak, -(snap.instant.accelMps2 ?: 0.0))
            else if (peak > 0) { brakePeaks += peak; peak = 0.0 }
            trip = snap.trip
            val done = controller.state.value.runningScript == null
            if (done && settledAt == null) settledAt = wall()
            if (settledAt != null && wall() - settledAt >= coastS * 1000L) break
            advanceTimeBy(rnd.nextLong(500, 1101)); runCurrent()
        }
        return Result(trip, (settledAt!! - startWall) / 1000.0, ecoSamples, brakePeaks)
    }

    @Test fun `script plays in wall time on a slow device`() = runTest {
        val r = play(DriveScript.HIGHWAY, seed = 1)
        // Before the fix: ≈ 185 s · 1.08 ≈ 200 s.
        assertEquals(DriveScript.HIGHWAY.durationS, r.wallS, 1.2)
        assertEquals("overspeed", 46.5, r.trip.overspeedS, 1.5)
    }

    @Test fun `city eco matches the golden on a slow device`() = runTest {
        for (seed in 1..5) {
            val eco = play(DriveScript.CITY, seed).trip.ecoS
            // Golden 91 s; jittery sampling alone moves it a few seconds, the old stretch gave 102–109 s.
            assertTrue("seed $seed eco=$eco", eco in 85.0..95.0)
        }
    }

    @Test fun `harsh eco matches the golden on a slow device`() = runTest {
        for (seed in 1..5) {
            val trip = play(DriveScript.HARSH, seed).trip
            // Golden 19 s; the 1 s window and 0.5–1.1 s sampling move it by up to one interval per phase.
            assertTrue("seed $seed eco=${trip.ecoS}", trip.ecoS in 17.0..21.5)
            assertEquals(2, trip.harshAccelCount)
            assertEquals(2, trip.harshBrakeCount)
        }
    }

    @Test fun `highway coast-down to a stop is eco at most once, at its onset`() = runTest {
        for (seed in 1..5) {
            val r = play(DriveScript.HIGHWAY, seed, coastS = 20)
            // The 2 m/s² coast itself is never eco. Only the first sample of the coast can be: its 1 s
            // window still half covers the 100 km/h cruise, and below 92 km/h the RPM is under 2500.
            assertTrue("seed $seed samples=${r.ecoSamples} eco=${r.trip.ecoS}", r.ecoSamples <= 1 && r.trip.ecoS <= 1.1 * LAG)
        }
    }

    /**
     * #142: with the player refreshing the simulator state only every (overrunning) tick, the dongle answered
     * with a value up to a tick old while the sample carried the read time. That biased City eco low
     * (mean ≈ 89 s, down to 81 s) and pushed a Harsh brake peak to +20 %. The dongle now evaluates the script
     * at the read itself, so a slow player tick changes nothing.
     */
    @Test fun `a slow player tick and per-PID read time neither bias eco nor inflate harsh peaks`() = runTest {
        val city = (1..30).map { play(DriveScript.CITY, it, playerTickMillis = 250, perPidMillis = 10).trip.ecoS }
        assertTrue("city eco $city", city.all { it in 86.0..96.0 })
        assertEquals("city eco mean", 91.0, city.average(), 1.5)
        for (seed in 1..30) {
            val r = play(DriveScript.HARSH, seed, playerTickMillis = 250, perPidMillis = 10)
            assertEquals(2, r.trip.harshBrakeCount)
            // Nominal 5.56 m/s²; the documented on-device bound is +15 %.
            assertTrue("seed $seed brake peaks ${r.brakePeaks}", r.brakePeaks.all { it <= 5.56 * 1.15 })
        }
    }

    private companion object { const val LAG = 1.08 }
}
