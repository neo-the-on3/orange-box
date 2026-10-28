package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.FuelSystemStatus
import io.github.neotheon3.orangebox.core.obd.pid.FuelType
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
        // (baro = MAP, i.e. wide open throttle, so VE is the full-load 0.85)
        val e = TelemetryEngine()
        assertEquals(23.443, e.speedDensityMaf(s(0, ObdPid.INTAKE_MAP to 60.0, ObdPid.INTAKE_TEMP to 30.0, ObdPid.BAROMETRIC_PRESSURE to 60.0), 3000.0)!!, 1e-3)
        assertNull(e.speedDensityMaf(s(0, ObdPid.INTAKE_MAP to 60.0), 3000.0))
    }

    @Test fun `speed density VE depends on load - 0_70 at idle up to 0_85 at wide open throttle (#194)`() {
        val e = TelemetryEngine()
        assertEquals(0.70, e.volumetricEfficiency(0.20), 1e-9)
        assertEquals(0.70, e.volumetricEfficiency(0.25), 1e-9)
        assertEquals(0.775, e.volumetricEfficiency(0.625), 1e-9)
        assertEquals(0.85, e.volumetricEfficiency(1.0), 1e-9)
        assertEquals(0.85, e.volumetricEfficiency(1.6), 1e-9) // boost: capped
        // Kiran's SX4 at idle: 749 rpm, MAP ~30 kPa, IAT 61 °C, 1.586 L → about 0.75 L/h (it was 0.87 L/h with a flat 0.85).
        e.vehicle = VehicleProfile(null, 1.586)
        val idle = e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 749.0, ObdPid.INTAKE_MAP to 30.0, ObdPid.INTAKE_TEMP to 61.0, ObdPid.BAROMETRIC_PRESSURE to 99.0)).instant
        assertEquals(0.75, idle.fuelFlowLph!!, 0.03)
        assertEquals("speed_density", e.fuelSource)
    }

    @Test fun `fuel cut relative to the learned closed-throttle position (#194)`() {
        // Kiran's SX4: absolute throttle reads 16.1 % with the pedal released.
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 750.0, ObdPid.THROTTLE to 16.5, ObdPid.MAF to 2.4))
        e.onSample(s(500, ObdPid.SPEED to 0.0, ObdPid.RPM to 749.0, ObdPid.THROTTLE to 16.1, ObdPid.MAF to 2.4))
        assertEquals(16.1, e.closedThrottlePercent!!, 1e-9)
        val coast = e.onSample(s(1_000, ObdPid.SPEED to 45.0, ObdPid.RPM to 2100.0, ObdPid.THROTTLE to 16.1, ObdPid.MAF to 4.0)).instant
        assertTrue("overrun at closed throttle is a cut", coast.fuelCut)
        assertEquals(0.0, coast.fuelFlowLph!!, 0.0)
        val light = e.onSample(s(1_500, ObdPid.SPEED to 46.0, ObdPid.RPM to 2100.0, ObdPid.THROTTLE to 20.0, ObdPid.MAF to 8.0)).instant
        assertFalse("pedal pressed", light.fuelCut)
        val veto = e.onSample(s(2_000, ObdPid.SPEED to 45.0, ObdPid.RPM to 1200.0, ObdPid.THROTTLE to 16.1, ObdPid.MAF to 3.0,
            ObdPid.FUEL_SYSTEM_STATUS to FuelSystemStatus.CLOSED_LOOP.toDouble())).instant
        assertFalse("ECU still in closed loop: injecting", veto.fuelCut)
        val confirmed = e.onSample(s(2_500, ObdPid.SPEED to 44.0, ObdPid.RPM to 1900.0, ObdPid.THROTTLE to 17.0,
            ObdPid.FUEL_SYSTEM_STATUS to FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT.toDouble())).instant
        assertTrue(confirmed.fuelCut)
    }

    @Test fun `drive-by-wire - fuel cut follows the pedal at rest, not the throttle plate (RDF1 drive 2)`() {
        // Synthetic values shaped like the SX4 log: pedal rests at 13.7 %, the ECU holds the plate at 15-24 % on overrun.
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 760.0, ObdPid.THROTTLE to 16.1, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 3.5))
        e.onSample(s(500, ObdPid.SPEED to 0.0, ObdPid.RPM to 750.0, ObdPid.THROTTLE to 14.5, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 3.4))
        assertEquals(13.7, e.restPedalPercent!!, 1e-9)
        val overrun = e.onSample(s(1_000, ObdPid.SPEED to 97.0, ObdPid.RPM to 4367.0, ObdPid.THROTTLE to 22.7, ObdPid.ACCELERATOR_PEDAL_D to 13.7,
            ObdPid.MAF to 14.4, ObdPid.FUEL_SYSTEM_STATUS to FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT.toDouble())).instant
        assertTrue("plate at 22.7 % but foot off: cut", overrun.fuelCut)
        assertEquals(0.0, overrun.fuelFlowLph!!, 0.0)
        val cruise = e.onSample(s(1_500, ObdPid.SPEED to 30.0, ObdPid.RPM to 2008.0, ObdPid.THROTTLE to 19.2, ObdPid.ACCELERATOR_PEDAL_D to 23.9, ObdPid.MAF to 8.5)).instant
        assertFalse("foot on the pedal", cruise.fuelCut)
        val closedLoop = e.onSample(s(2_000, ObdPid.SPEED to 22.0, ObdPid.RPM to 1500.0, ObdPid.THROTTLE to 16.9, ObdPid.ACCELERATOR_PEDAL_D to 13.7,
            ObdPid.MAF to 5.8, ObdPid.FUEL_SYSTEM_STATUS to FuelSystemStatus.CLOSED_LOOP.toDouble())).instant
        assertFalse("ECU still injecting (closed loop)", closedLoop.fuelCut)
        val noStatus = e.onSample(s(2_500, ObdPid.SPEED to 40.0, ObdPid.RPM to 1800.0, ObdPid.THROTTLE to 21.0, ObdPid.ACCELERATOR_PEDAL_D to 14.1, ObdPid.MAF to 6.0)).instant
        assertTrue("within 2 % of rest", noStatus.fuelCut)
        e.reset()
        assertNull(e.restPedalPercent)
    }

    @Test fun `without a learned closed position the fixed 8 pct still applies, and a new connection relearns (#194)`() {
        val e = TelemetryEngine()
        assertFalse(e.onSample(s(0, ObdPid.SPEED to 45.0, ObdPid.RPM to 2100.0, ObdPid.THROTTLE to 16.1)).instant.fuelCut)
        assertTrue(e.onSample(s(500, ObdPid.SPEED to 45.0, ObdPid.RPM to 2100.0, ObdPid.THROTTLE to 6.0)).instant.fuelCut)
        e.onSample(s(1_000, ObdPid.SPEED to 0.0, ObdPid.RPM to 750.0, ObdPid.THROTTLE to 14.0))
        assertEquals("learned only while idling", 14.0, e.closedThrottlePercent!!, 1e-9)
        e.reset()
        assertNull(e.closedThrottlePercent)
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 750.0, ObdPid.THROTTLE to 40.0))
        assertNull("fast idle with the pedal pressed isn't learned", e.closedThrottlePercent)
    }

    @Test fun `coasting at closed throttle adds no trip fuel once the closed position is learned (#194)`() {
        // 20 s idle, 20 s cruise at 30 % throttle, 20 s coast at closed throttle (16 %); speed density, SX4.
        val e = TelemetryEngine().apply { vehicle = VehicleProfile(null, 1.586) }
        var t = 0L
        fun step(speed: Double, rpm: Double, thr: Double, map: Double): TripTotals = e.onSample(
            s(t, ObdPid.SPEED to speed, ObdPid.RPM to rpm, ObdPid.THROTTLE to thr, ObdPid.INTAKE_MAP to map,
                ObdPid.INTAKE_TEMP to 40.0, ObdPid.BAROMETRIC_PRESSURE to 99.0),
        ).trip.also { t += 500 }
        repeat(40) { step(0.0, 750.0, 16.0, 30.0) }
        var trip = TripTotals()
        repeat(40) { trip = step(40.0, 2000.0, 30.0, 55.0) }
        val afterCruise = trip.fuelLitres
        repeat(40) { trip = step(35.0, 1800.0, 16.0, 22.0) }
        assertEquals("only the transition interval", afterCruise, trip.fuelLitres, 0.0005)
        assertEquals(19.5, trip.fuelCutS, 0.6)
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
        val e4 = TelemetryEngine()
        e4.onSample(s(0, ObdPid.SPEED to 50.0))
        val braking = e4.onSample(s(1000, ObdPid.SPEED to 37.0)).instant
        assertTrue(braking.harshBrake); assertFalse(braking.harshAccel)
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

    @Test fun `vehicle profile sets fuel type and displacement, but PID 51 still wins`() {
        val maf = s(0, ObdPid.MAF to 10.0, ObdPid.RPM to 2000.0, ObdPid.SPEED to 50.0, ObdPid.THROTTLE to 20.0)
        val petrol = TelemetryEngine().onSample(maf).instant.fuelFlowLph!!
        val diesel = TelemetryEngine().also { it.vehicle = VehicleProfile(FuelType.DIESEL, null) }.onSample(maf).instant.fuelFlowLph!!
        assertTrue("diesel AFR/density differ", kotlin.math.abs(petrol - diesel) > 0.1)
        val reported = TelemetryEngine().also { it.vehicle = VehicleProfile(FuelType.DIESEL, null) }
            .onSample(s(0, ObdPid.MAF to 10.0, ObdPid.FUEL_TYPE to 1.0, ObdPid.RPM to 2000.0, ObdPid.SPEED to 50.0, ObdPid.THROTTLE to 20.0))
        assertEquals(petrol, reported.instant.fuelFlowLph!!, 1e-9)

        val sd = s(0, ObdPid.INTAKE_MAP to 60.0, ObdPid.INTAKE_TEMP to 30.0, ObdPid.RPM to 3000.0, ObdPid.SPEED to 50.0, ObdPid.THROTTLE to 20.0)
        val small = TelemetryEngine().onSample(sd).instant.fuelFlowLph!!
        val big = TelemetryEngine().also { it.vehicle = VehicleProfile(null, 3.2) }.onSample(sd).instant.fuelFlowLph!!
        assertEquals(2.0, big / small, 1e-9)
    }

    @Test fun `ECU-reported fuel cut with the foot off counts below 20 km-h (#209)`() {
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 760.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 3.5))
        val cut = FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT.toDouble()
        val slow = e.onSample(s(500, ObdPid.SPEED to 12.0, ObdPid.RPM to 1300.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 4.0, ObdPid.FUEL_SYSTEM_STATUS to cut)).instant
        assertTrue("decel cut at 12 km/h, foot off", slow.fuelCut)
        assertEquals(0.0, slow.fuelFlowLph!!, 0.0)
        val lowRpm = e.onSample(s(1_000, ObdPid.SPEED to 8.0, ObdPid.RPM to 950.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 4.0, ObdPid.FUEL_SYSTEM_STATUS to cut)).instant
        assertFalse("below 1000 rpm the ECU resumes fuel", lowRpm.fuelCut)
        val closedLoop = e.onSample(s(1_500, ObdPid.SPEED to 12.0, ObdPid.RPM to 1300.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 4.0, ObdPid.FUEL_SYSTEM_STATUS to 2.0)).instant
        assertFalse("no ECU cut, under 20 km/h", closedLoop.fuelCut)
        val pressed = e.onSample(s(2_000, ObdPid.SPEED to 12.0, ObdPid.RPM to 1300.0, ObdPid.ACCELERATOR_PEDAL_D to 40.0, ObdPid.MAF to 4.0, ObdPid.FUEL_SYSTEM_STATUS to cut)).instant
        assertFalse("open loop with the pedal pressed is enrichment", pressed.fuelCut)
        val stopped = e.onSample(s(2_500, ObdPid.SPEED to 0.0, ObdPid.RPM to 1300.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 4.0, ObdPid.FUEL_SYSTEM_STATUS to cut)).instant
        assertFalse("not moving", stopped.fuelCut)
    }

    @Test fun `open-loop enrichment burns more fuel, PID 44 wins when reported (#208)`() {
        val e = TelemetryEngine()
        e.onSample(s(0, ObdPid.SPEED to 0.0, ObdPid.RPM to 760.0, ObdPid.ACCELERATOR_PEDAL_D to 13.7, ObdPid.MAF to 3.5))
        val base = e.onSample(s(500, ObdPid.SPEED to 80.0, ObdPid.RPM to 3500.0, ObdPid.ACCELERATOR_PEDAL_D to 60.0, ObdPid.MAF to 40.0, ObdPid.FUEL_SYSTEM_STATUS to 2.0)).instant
        val rich = e.onSample(s(1_000, ObdPid.SPEED to 80.0, ObdPid.RPM to 3500.0, ObdPid.ACCELERATOR_PEDAL_D to 60.0, ObdPid.MAF to 40.0, ObdPid.FUEL_SYSTEM_STATUS to 4.0)).instant
        assertEquals("λ 0.85 assumed", 1 / 0.85, rich.fuelFlowLph!! / base.fuelFlowLph!!, 1e-9)
        val reported = e.onSample(s(1_500, ObdPid.SPEED to 80.0, ObdPid.RPM to 3500.0, ObdPid.ACCELERATOR_PEDAL_D to 60.0, ObdPid.MAF to 40.0,
            ObdPid.FUEL_SYSTEM_STATUS to 4.0, ObdPid.COMMANDED_EQUIVALENCE_RATIO to 0.9)).instant
        assertEquals(1 / 0.9, reported.fuelFlowLph!! / base.fuelFlowLph!!, 1e-9)
    }

    @Test fun `petrol blends mix ethanol by volume and AFR by mass (#208)`() {
        val e20 = FuelProperties.petrol(20)
        assertEquals(13.5, e20.stoichAfr, 0.05)
        assertEquals(753.8, e20.densityGramsPerLitre, 0.05)
        assertEquals(2150.0, e20.co2GramsPerLitre, 0.5)
        assertEquals(14.1, FuelProperties.petrol(10).stoichAfr, 0.05)
        assertEquals(FuelProperties.GASOLINE, FuelProperties.petrol(0))
        val maf = s(0, ObdPid.MAF to 10.0, ObdPid.RPM to 2000.0, ObdPid.SPEED to 50.0, ObdPid.THROTTLE to 20.0)
        val e0Flow = TelemetryEngine().onSample(maf).instant.fuelFlowLph!!
        val e20Flow = TelemetryEngine().also { it.vehicle = VehicleProfile(FuelType.GASOLINE, null, petrolEthanolPercent = 20) }.onSample(maf).instant.fuelFlowLph!!
        assertEquals("E20 ≈ 7.5 % more litres for the same air", 1.075, e20Flow / e0Flow, 0.005)
        val diesel = TelemetryEngine().also { it.vehicle = VehicleProfile(FuelType.DIESEL, null, petrolEthanolPercent = 20) }.onSample(maf).instant.fuelFlowLph!!
        val dieselE0 = TelemetryEngine().also { it.vehicle = VehicleProfile(FuelType.DIESEL, null) }.onSample(maf).instant.fuelFlowLph!!
        assertEquals("blend only applies to petrol", dieselE0, diesel, 1e-12)
    }
}
