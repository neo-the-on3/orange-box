package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.FuelSystemStatus
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each expectation is computed by hand in the comment next to it. */
class TelemetryEngineTest {
    private fun s(t: Long, vararg v: Pair<ObdPid, Double>) = VehicleSample(t, mapOf(*v))

    @Test fun `fuel flow from MAF for gasoline`() {
        // 10 g/s · 3600 / (14.7 · 745) = 3.2873 L/h
        val e = TelemetryEngine()
        val i = e.onSample(s(0, ObdPid.MAF to 10.0, ObdPid.RPM to 2000.0, ObdPid.SPEED to 50.0, ObdPid.THROTTLE to 20.0)).instant
        assertEquals(3.2873, i.fuelFlowLph!!, 1e-4)
        // economy = 3.28722 / 50 · 100 = 6.5744 L/100 km
        assertEquals(6.5744, i.economyLPer100Km!!, 1e-4)
    }

    @Test fun `fuel flow for diesel reported by PID 51 and lambda`() {
        // diesel: 10 · 3600 / (14.5 · 1.5 · 832) = 1.9894 L/h
        val e = TelemetryEngine()
        val i = e.onSample(s(0, ObdPid.MAF to 10.0, ObdPid.FUEL_TYPE to 4.0, ObdPid.COMMANDED_EQUIVALENCE_RATIO to 1.5, ObdPid.THROTTLE to 30.0)).instant
        assertEquals(1.9894, i.fuelFlowLph!!, 1e-4)
    }

    @Test fun `PID 5E fuel rate wins over MAF`() {
        val i = TelemetryEngine().onSample(s(0, ObdPid.FUEL_RATE to 7.5, ObdPid.MAF to 10.0, ObdPid.THROTTLE to 30.0)).instant
        assertEquals(7.5, i.fuelFlowLph!!, 0.0)
    }

    @Test fun `speed density fallback`() {
        // MAF = 3000/120 · 0.0016 · 0.85 · (60000 · 0.0289647 / (8.314462618 · 303.15)) · 1000
        //     = 25 · 0.0016 · 0.85 · 0.68950 · 1000 = 23.443 g/s
        val e = TelemetryEngine()
        assertEquals(23.443, e.speedDensityMaf(s(0, ObdPid.INTAKE_MAP to 60.0, ObdPid.INTAKE_TEMP to 30.0), 3000.0)!!, 1e-3)
        assertNull(e.speedDensityMaf(s(0, ObdPid.INTAKE_MAP to 60.0), 3000.0))
    }

    @Test fun `no economy when stationary`() {
        val i = TelemetryEngine().onSample(s(0, ObdPid.MAF to 2.5, ObdPid.SPEED to 0.0, ObdPid.RPM to 800.0)).instant
        assertNull(i.economyLPer100Km)
        assertTrue(i.idling)
        assertFalse(i.eco)
    }

    @Test fun `fuel cut from low throttle or ECU status`() {
        val e = TelemetryEngine()
        val a = e.onSample(s(0, ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 0.0, ObdPid.MAF to 3.0)).instant
        assertTrue(a.fuelCut); assertEquals(0.0, a.fuelFlowLph!!, 0.0)
        val b = TelemetryEngine().onSample(s(0, ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.FUEL_SYSTEM_STATUS to FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT.toDouble())).instant
        assertTrue(b.fuelCut)
        val c = TelemetryEngine().onSample(s(0, ObdPid.SPEED to 15.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 0.0)).instant
        assertFalse("below 20 km/h", c.fuelCut)
        val d = TelemetryEngine().onSample(s(0, ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 30.0, ObdPid.FUEL_SYSTEM_STATUS to 4.0)).instant
        assertFalse("open loop under load is not a cut", d.fuelCut)
    }

    @Test fun `trapezoid distance, fuel and co2`() {
        val e = TelemetryEngine()
        // 0 → 36 km/h over 4 s: distance = 18 km/h avg · 4 s = 0.02 km
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 900.0, ObdPid.FUEL_RATE to 2.0, ObdPid.THROTTLE to 30.0))
        val t = e.onSample(s(4_000, ObdPid.SPEED to 36.0, ObdPid.RPM to 1800.0, ObdPid.FUEL_RATE to 4.0, ObdPid.THROTTLE to 30.0)).trip
        assertEquals(0.02, t.distanceKm, 1e-9)
        // fuel = 3 L/h avg · 4 s = 0.0033333 L; CO₂ = 0.0033333 · 2310 = 7.7 g
        assertEquals(0.0033333, t.fuelLitres, 1e-6)
        assertEquals(7.7, t.co2Grams, 1e-3)
        // trip economy needs ≥ 0.1 km
        assertNull(t.economyLPer100Km)
        assertEquals(4.0, t.durationS, 0.0)
        assertEquals(36.0, t.maxSpeedKph, 0.0)
        assertEquals(18.0, t.averageSpeedKph!!, 1e-9)
        // keep going at 36 km/h for 10 × 4 s → +0.4 km, fuel 4 L/h · 40 s = 0.044444 L
        var trip = t
        for (k in 2..11) trip = e.onSample(s(k * 4_000L, ObdPid.SPEED to 36.0, ObdPid.RPM to 1800.0, ObdPid.FUEL_RATE to 4.0, ObdPid.THROTTLE to 30.0)).trip
        assertEquals(0.42, trip.distanceKm, 1e-9)
        // (0.0033333 + 0.0444444) / 0.42 · 100 = 11.376 L/100 km
        assertEquals(11.376, trip.economyLPer100Km!!, 1e-3)
    }

    @Test fun `gaps over 5 s are not integrated`() {
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 50.0))
        val t = e.onSample(s(6_000, ObdPid.SPEED to 50.0)).trip
        assertEquals(0.0, t.distanceKm, 0.0)
        assertEquals(0.0, t.durationS, 0.0)
    }

    @Test fun `harsh accel and brake counted once per event`() {
        val e = TelemetryEngine()
        // +11 km/h per second = 3.06 m/s² (≥ 3.0, counts); held for 2 s → still 1 event
        var t = 0L
        var v = 0.0
        e.onSample(s(t, ObdPid.SPEED to v))
        repeat(2) { t += 1000; v += 11.0; e.onSample(s(t, ObdPid.SPEED to v)) }
        t += 1000; e.onSample(s(t, ObdPid.SPEED to v)) // steady: event ends
        // −13 km/h per second = −3.61 m/s² (≥ 3.5) → 1 brake event
        t += 1000; v -= 13.0
        val trip = e.onSample(s(t, ObdPid.SPEED to v)).trip
        assertEquals(1, trip.harshAccelCount)
        assertEquals(1, trip.harshBrakeCount)
        // 2.9 m/s² stays below the threshold
        val e2 = TelemetryEngine()
        e2.onSample(s(0, ObdPid.SPEED to 0.0))
        assertEquals(0, e2.onSample(s(1000, ObdPid.SPEED to 10.44)).trip.harshAccelCount)
        // configurable
        val e3 = TelemetryEngine(TelemetryConfig(harshAccel = 2.5))
        e3.onSample(s(0, ObdPid.SPEED to 0.0))
        assertEquals(1, e3.onSample(s(1000, ObdPid.SPEED to 10.44)).trip.harshAccelCount)
    }

    @Test fun `idle, eco, overspeed and zone times use the state at the start of each interval`() {
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 800.0))      // idle for 2 s
        e.onSample(s(2_000, ObdPid.SPEED to 50.0, ObdPid.RPM to 2000.0)) // eco, zone 31-60 for 2 s (accel 6.9 m/s² → not eco at this sample)
        e.onSample(s(4_000, ObdPid.SPEED to 50.0, ObdPid.RPM to 2000.0)) // eco for 2 s
        e.onSample(s(6_000, ObdPid.SPEED to 130.0, ObdPid.RPM to 3500.0)) // overspeed for 2 s, zone >120
        val trip = e.onSample(s(8_000, ObdPid.SPEED to 130.0, ObdPid.RPM to 3500.0)).trip
        assertEquals(2.0, trip.idleS, 0.0)
        assertEquals(2.0, trip.ecoS, 0.0)
        assertEquals(2.0, trip.overspeedS, 0.0)
        assertEquals(listOf(2.0, 0.0, 4.0, 0.0, 0.0, 2.0), trip.speedZoneS)
    }

    @Test fun `warnings`() {
        val w = TelemetryEngine().onSample(
            s(0, ObdPid.COOLANT_TEMP to 112.0, ObdPid.CONTROL_MODULE_VOLTAGE to 11.5, ObdPid.FUEL_LEVEL to 8.0),
        ).instant.warnings
        assertEquals(setOf(Warning.COOLANT_OVERHEAT, Warning.LOW_BATTERY, Warning.LOW_FUEL), w)
        assertEquals(setOf(Warning.OVER_VOLTAGE), TelemetryEngine().onSample(s(0, ObdPid.CONTROL_MODULE_VOLTAGE to 15.5)).instant.warnings)
        assertTrue(TelemetryEngine().onSample(s(0, ObdPid.COOLANT_TEMP to 90.0)).instant.warnings.isEmpty())
    }

    @Test fun `reset clears totals`() {
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 50.0)); e.onSample(s(1000, ObdPid.SPEED to 50.0))
        e.reset()
        assertEquals(0.0, e.onSample(s(2000, ObdPid.SPEED to 50.0)).trip.distanceKm, 0.0)
    }
}
