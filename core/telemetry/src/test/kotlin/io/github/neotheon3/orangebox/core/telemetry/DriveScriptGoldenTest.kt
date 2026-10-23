package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.pid.PollingConfig
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.simulator.DriveScript
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Golden tests: replay each scripted drive through the simulated ELM327 → Elm327Session → PID decoding →
 * TelemetryEngine, sampling every 0.5 s, and compare the trip totals with recorded values.
 * Distance is also checked against the analytic integral of the script (hand-computable).
 * If a formula changes on purpose, update the golden numbers here and docs/telemetry.md together.
 */
class DriveScriptGoldenTest {
    private data class Golden(
        val distanceKm: Double, val fuelL: Double, val idleS: Double, val fuelCutS: Double,
        val ecoS: Double, val overspeedS: Double, val harshAccel: Int, val harshBrake: Int,
    )

    private suspend fun run(script: DriveScript): TripTotals {
        val controller = SimulatorController()
        val transport = SimulatedElm327Transport(controller).also { it.open() }
        val session = Elm327Session(transport).also { it.initialize() }
        val supported = session.readSupportedPids()
        val pids = supported.filter(PollingConfig().fast + PollingConfig().slow)
        val engine = TelemetryEngine()
        var last: TripTotals? = null
        var tMs = 0L
        while (tMs <= (script.durationS * 1000).toLong()) {
            val f = script.frameAt(tMs / 1000.0)
            controller.update { it.copy(speedKph = f.speedKph.roundToInt(), rpm = f.rpm, throttlePercent = f.throttlePercent) }
            val values = pids.mapNotNull { p -> session.read(p)?.let { p to it } }.toMap()
            last = engine.onSample(VehicleSample(tMs, values)).trip
            tMs += 500
        }
        return last!!
    }

    private fun check(script: DriveScript, g: Golden) = runTest {
        val t = run(script)
        println("${script.id}: $t economy=${t.economyLPer100Km}")
        // Hand check: within 1.5% of the analytic distance (PID 0D is whole km/h).
        val analytic = script.distanceKm()
        if (analytic > 0) assertEquals(analytic, t.distanceKm, analytic * 0.015)
        assertEquals("distance", g.distanceKm, t.distanceKm, 0.001)
        assertEquals("fuel", g.fuelL, t.fuelLitres, 0.0005)
        assertEquals("co2", g.fuelL * FuelProperties.GASOLINE.co2GramsPerLitre, t.co2Grams, 2.0)
        assertEquals("idle", g.idleS, t.idleS, 0.01)
        assertEquals("fuel cut", g.fuelCutS, t.fuelCutS, 0.01)
        assertEquals("eco", g.ecoS, t.ecoS, 0.01)
        assertEquals("overspeed", g.overspeedS, t.overspeedS, 0.01)
        assertEquals("harsh accel", g.harshAccel, t.harshAccelCount)
        assertEquals("harsh brake", g.harshBrake, t.harshBrakeCount)
        assertEquals(script.durationS, t.durationS, 0.5)
        assertEquals(t.durationS, t.speedZoneS.sum(), 1e-6)
    }

    @Test fun idle() = check(DriveScript.IDLE, GOLDEN.getValue("idle"))
    @Test fun city() = check(DriveScript.CITY, GOLDEN.getValue("city"))
    @Test fun highway() = check(DriveScript.HIGHWAY, GOLDEN.getValue("highway"))
    @Test fun harsh() = check(DriveScript.HARSH, GOLDEN.getValue("harsh"))

    @Test fun `highway overspeed ends below the 117 km per h release point`() = runTest {
        val lines = mutableListOf<String>()
        io.github.neotheon3.orangebox.core.common.OrangeLog.sink =
            io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, tag, msg, _ -> lines += "$tag $msg" }
        try {
            run(DriveScript.HIGHWAY)
        } finally {
            io.github.neotheon3.orangebox.core.common.OrangeLog.sink = io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, _, _, _ -> }
        }
        val ends = lines.filter { it.startsWith("OrangeBox/Telemetry overspeed_end ") }
        assertTrue(lines.toString(), ends.isNotEmpty())
        for (end in ends) {
            val speed = Regex(" speed_kmh=([0-9.]+) ").find(end)!!.groupValues[1].toDouble()
            assertTrue(end, speed < 117.0)
        }
    }

    @Test fun `scripts are plausible`() = runTest {
        val idle = run(DriveScript.IDLE)
        // 120 s idling at ~2.5 g/s MAF → ≈ 0.82 L/h → ≈ 0.027 L
        assertTrue(idle.fuelLitres in 0.02..0.035)
        assertEquals(120.0, idle.idleS, 0.01)
        val highway = run(DriveScript.HIGHWAY)
        assertTrue("highway overspeed", highway.overspeedS > 30)
        val city = run(DriveScript.CITY)
        assertTrue("city uses more fuel per km than highway", city.economyLPer100Km!! > highway.economyLPer100Km!!)
    }

    private companion object {
        // Recorded 2026-10-23, re-recorded 2026-10-24 after windowed acceleration + event hysteresis
        // (city/harsh eco and highway overspeed changed; harsh counts unchanged at 2/2); distance also matches the analytic integral within 1.5%.
        val GOLDEN = mapOf(
            "idle" to Golden(0.0, 0.02750, idleS = 120.0, fuelCutS = 0.0, ecoS = 0.0, overspeedS = 0.0, harshAccel = 0, harshBrake = 0),
            "city" to Golden(1.21083, 0.11622, idleS = 31.5, fuelCutS = 13.5, ecoS = 91.0, overspeedS = 0.0, harshAccel = 0, harshBrake = 0),
            "highway" to Golden(4.78681, 0.24508, idleS = 0.5, fuelCutS = 15.0, ecoS = 0.0, overspeedS = 46.5, harshAccel = 0, harshBrake = 0),
            "harsh" to Golden(0.45056, 0.04384, idleS = 11.0, fuelCutS = 4.0, ecoS = 19.0, overspeedS = 0.0, harshAccel = 2, harshBrake = 2),
        )
    }
}
