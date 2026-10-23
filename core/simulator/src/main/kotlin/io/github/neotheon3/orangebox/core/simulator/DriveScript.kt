package io.github.neotheon3.orangebox.core.simulator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A piece of a drive: change speed linearly to [endKph] over [durationS] seconds. */
data class Segment(val durationS: Double, val endKph: Double)

/** One instant of a script: what the simulated car reports. */
data class DriveFrame(val tS: Double, val speedKph: Double, val rpm: Int, val throttlePercent: Int, val accelMps2: Double)

/**
 * Deterministic scripted drive. Speed is piecewise linear; RPM comes from a 5-speed gear model and the
 * throttle from the acceleration demand (0 while decelerating, so coasting above 20 km/h is a fuel cut).
 */
class DriveScript(val id: String, val label: String, private val segments: List<Segment>) {
    val durationS: Double = segments.sumOf { it.durationS }

    fun frameAt(tS: Double): DriveFrame {
        var t0 = 0.0
        var v0 = 0.0
        for (seg in segments) {
            if (tS <= t0 + seg.durationS) {
                val f = if (seg.durationS == 0.0) 1.0 else (tS - t0) / seg.durationS
                val v = v0 + (seg.endKph - v0) * f
                val accel = if (seg.durationS == 0.0) 0.0 else (seg.endKph - v0) / 3.6 / seg.durationS
                return frame(tS, v, accel)
            }
            t0 += seg.durationS
            v0 = seg.endKph
        }
        return frame(tS, v0, 0.0)
    }

    /** Analytic distance of the whole script, km (exact for piecewise-linear speed). */
    fun distanceKm(): Double {
        var v0 = 0.0
        return segments.sumOf { s -> ((v0 + s.endKph) / 2 * s.durationS / 3600.0).also { v0 = s.endKph } }
    }

    private fun frame(t: Double, v: Double, accel: Double): DriveFrame {
        val rpm = gearRpm(v)
        val throttle = when {
            accel > 0.1 -> (15 + accel * 20 + v / 10).coerceAtMost(100.0)
            accel < -0.1 -> 0.0
            v < 1 -> 0.0
            else -> 10 + v / 10
        }
        return DriveFrame(t, v, rpm, throttle.roundToInt(), accel)
    }

    companion object {
        /** km/h → rpm factor per gear, with the upper speed of each gear. */
        private val GEARS = listOf(15.0 to 110.0, 30.0 to 70.0, 50.0 to 48.0, 75.0 to 36.0, Double.MAX_VALUE to 27.0)

        fun gearRpm(v: Double): Int {
            if (v < 1) return SimulatorState.IDLE_RPM
            val k = GEARS.first { v <= it.first }.second
            return (v * k).coerceAtLeast(900.0).roundToInt()
        }

        val IDLE = DriveScript("idle", "Idle (2 min)", listOf(Segment(120.0, 0.0)))

        val CITY = DriveScript(
            "city", "City stop-and-go",
            List(3) { listOf(Segment(10.0, 50.0), Segment(20.0, 50.0), Segment(8.0, 0.0), Segment(10.0, 0.0)) }.flatten(),
        )

        val HIGHWAY = DriveScript(
            "highway", "Highway",
            listOf(Segment(15.0, 100.0), Segment(60.0, 120.0), Segment(10.0, 130.0), Segment(30.0, 130.0), Segment(15.0, 100.0), Segment(30.0, 100.0)),
        )

        /** 0→60 in 4 s (4.2 m/s²) and 60→0 in 3 s (5.6 m/s²), twice. */
        val HARSH = DriveScript(
            "harsh", "Harsh accel/brake",
            List(2) { listOf(Segment(4.0, 60.0), Segment(10.0, 60.0), Segment(3.0, 0.0), Segment(5.0, 0.0)) }.flatten(),
        )

        val ALL = listOf(IDLE, CITY, HIGHWAY, HARSH)
        fun byId(id: String) = ALL.firstOrNull { it.id == id }
    }
}

/**
 * Plays a [DriveScript] into a [SimulatorController] in real (or virtual) time. When the script ends or
 * is stopped the car coasts to a halt: throttle 0, speed falls by [decayKphPerSecond] (≈ 2 m/s², gentle
 * braking, so it doesn't count as harsh), RPM follows the gear model down to idle.
 *
 * Script time is read from [clock] (monotonic milliseconds), not counted in [tickMillis] steps: on a slow
 * device each `delay(tickMillis)` overruns, and counting ticks stretched the whole drive in wall time
 * (≈ 8 % on an API 25 ARM emulator), which lowered every real acceleration by the same factor. City's
 * 1.74 m/s² stops then fell under the 1.5 m/s² eco limit and were counted as eco (issue #120).
 * [tickMillis] now only sets how often the frame is refreshed.
 */
class DriveScriptPlayer(
    private val controller: SimulatorController,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 100,
    private val decayKphPerSecond: Double = 7.2,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var job: Job? = null

    fun start(script: DriveScript) {
        job?.cancel()
        controller.update { it.copy(engineOn = true, runningScript = script.id) }
        job = scope.launch {
            val startedAt = clock()
            while (true) {
                val t = (clock() - startedAt) / 1000.0
                val f = script.frameAt(t.coerceAtMost(script.durationS))
                controller.update {
                    it.copy(speedKph = f.speedKph.roundToInt(), rpm = f.rpm, throttlePercent = f.throttlePercent)
                }
                if (t >= script.durationS) break
                delay(tickMillis)
            }
            controller.update { it.copy(runningScript = null) }
            coastToIdle()
        }
    }

    /** Stops the script; the car then coasts to 0 km/h and idle RPM. */
    fun stop() {
        val wasRunning = job?.isActive == true
        job?.cancel()
        job = null
        controller.update { if (it.runningScript == null) it else it.copy(runningScript = null, throttlePercent = 0) }
        if (wasRunning) job = scope.launch { coastToIdle() }
    }

    /** Speed falls linearly with elapsed [clock] time, so a late tick can't slow the deceleration. */
    private suspend fun coastToIdle() {
        val v0 = controller.state.value.speedKph.toDouble()
        val startedAt = clock()
        while (true) {
            val v = (v0 - decayKphPerSecond * (clock() - startedAt) / 1000.0).coerceAtLeast(0.0)
            controller.update { it.copy(speedKph = v.roundToInt(), rpm = DriveScript.gearRpm(v), throttlePercent = 0) }
            if (v <= 0.0) break
            delay(tickMillis)
        }
    }
}
