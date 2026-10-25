package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.MonitorStatus
import io.github.neotheon3.orangebox.core.obd.pid.FuelType
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #99: eco level, coolant/battery warnings, instant km/L and MIL state for the dashboard. */
class DashboardStateTest {
    private fun s(vararg v: Pair<ObdPid, Double>, monitor: MonitorStatus? = null) = VehicleSample(0, mapOf(*v), monitor)
    private fun instant(vararg v: Pair<ObdPid, Double>, monitor: MonitorStatus? = null, vehicle: VehicleProfile? = null) =
        TelemetryEngine().apply { this.vehicle = vehicle }.onSample(s(*v, monitor = monitor)).instant

    @Test fun `petrol eco level from engine load, only while moving`() {
        val moving = ObdPid.SPEED to 40.0
        assertEquals(1, instant(moving, ObdPid.ENGINE_LOAD to 49.9).ecoLevel)
        assertEquals(2, instant(moving, ObdPid.ENGINE_LOAD to 50.0).ecoLevel)
        assertEquals(3, instant(moving, ObdPid.ENGINE_LOAD to 80.0).ecoLevel)
        assertEquals(4, instant(moving, ObdPid.ENGINE_LOAD to 90.0).ecoLevel)
        assertEquals("stopped", 0, instant(ObdPid.SPEED to 0.0, ObdPid.ENGINE_LOAD to 95.0).ecoLevel)
        assertEquals("no load PID", 0, instant(moving).ecoLevel)
    }

    @Test fun `diesel eco level from boost`() {
        val diesel = VehicleProfile(FuelType.DIESEL, 2.0)
        val moving = ObdPid.SPEED to 60.0
        // MAP 150 − baro 101 = 49 kPa → 1; 181 − 101 = 80 → 3; 201 − 101 = 100 → 4
        assertEquals(1, instant(moving, ObdPid.INTAKE_MAP to 150.0, ObdPid.BAROMETRIC_PRESSURE to 101.0, ObdPid.ENGINE_LOAD to 99.0, vehicle = diesel).ecoLevel)
        assertEquals(3, instant(moving, ObdPid.INTAKE_MAP to 181.0, ObdPid.BAROMETRIC_PRESSURE to 101.0, vehicle = diesel).ecoLevel)
        assertEquals(4, instant(moving, ObdPid.INTAKE_MAP to 201.0, ObdPid.BAROMETRIC_PRESSURE to 101.0, vehicle = diesel).ecoLevel)
        // No baro: legacy default 100 kPa → 150 − 100 = 50 → 2
        assertEquals(2, instant(moving, ObdPid.INTAKE_MAP to 150.0, vehicle = diesel).ecoLevel)
        assertEquals("legacy: diesel without MAP has no level (no load fallback)", 0, instant(moving, ObdPid.ENGINE_LOAD to 95.0, vehicle = diesel).ecoLevel)
    }

    @Test fun `RPM panel level is exactly the legacy load band, per level including red (#198)`() {
        val moving = ObdPid.SPEED to 40.0
        val running = ObdPid.RPM to 1800.0
        assertEquals("green", 1, instant(moving, running, ObdPid.ENGINE_LOAD to 30.0).ecoLevel)
        assertEquals("amber", 2, instant(moving, running, ObdPid.ENGINE_LOAD to 60.0).ecoLevel)
        assertEquals("amber", 3, instant(moving, running, ObdPid.ENGINE_LOAD to 85.0).ecoLevel)
        assertEquals("red", 4, instant(moving, running, ObdPid.ENGINE_LOAD to 92.0).ecoLevel)
        assertEquals("red at 100 %", 4, instant(moving, running, ObdPid.ENGINE_LOAD to 100.0).ecoLevel)
        // No rpm/acceleration step any more: CarooLive had none.
        assertEquals("high rpm, light load: green", 1, instant(moving, ObdPid.RPM to 4200.0, ObdPid.ENGINE_LOAD to 30.0).ecoLevel)
    }

    @Test fun `RPM panel level at idle, stopped, engine off and during fuel cut (#198)`() {
        assertEquals("idle at a light", 0, instant(ObdPid.SPEED to 0.0, ObdPid.RPM to 750.0, ObdPid.ENGINE_LOAD to 35.0).ecoLevel)
        assertEquals("stopped, revving", 0, instant(ObdPid.SPEED to 0.0, ObdPid.RPM to 4000.0, ObdPid.ENGINE_LOAD to 95.0).ecoLevel)
        assertEquals("rolling with the engine off", 0, instant(ObdPid.SPEED to 20.0, ObdPid.RPM to 0.0, ObdPid.ENGINE_LOAD to 0.0).ecoLevel)
        assertEquals("no load PID", 0, instant(ObdPid.SPEED to 40.0, ObdPid.RPM to 1800.0).ecoLevel)
        // Overrun at closed throttle: a fuel cut, low calculated load → green, like CarooLive.
        val cut = instant(ObdPid.SPEED to 60.0, ObdPid.RPM to 2500.0, ObdPid.THROTTLE to 5.0, ObdPid.ENGINE_LOAD to 20.0)
        assertTrue(cut.fuelCut)
        assertEquals(1, cut.ecoLevel)
    }

    @Test fun `load bands are configurable (#198)`() {
        val e = TelemetryEngine(TelemetryConfig(ecoLoadThresholds = listOf(40.0, 60.0, 75.0)))
        val i = e.onSample(VehicleSample(0, mapOf(ObdPid.SPEED to 50.0, ObdPid.RPM to 3_100.0, ObdPid.ENGINE_LOAD to 76.0))).instant
        assertEquals(4, i.ecoLevel)
    }

    @Test fun `coolant warning from 100 C`() {
        assertFalse(instant(ObdPid.COOLANT_TEMP to 99.0).coolantWarning)
        assertTrue(instant(ObdPid.COOLANT_TEMP to 100.0).coolantWarning)
        assertFalse(instant().coolantWarning)
    }

    @Test fun `battery warning outside 13_2 to 14_8 V with the engine on`() {
        val on = ObdPid.RPM to 800.0
        assertFalse(instant(on, ObdPid.CONTROL_MODULE_VOLTAGE to 14.1).batteryWarning)
        assertTrue(instant(on, ObdPid.CONTROL_MODULE_VOLTAGE to 13.1).batteryWarning)
        assertTrue(instant(on, ObdPid.CONTROL_MODULE_VOLTAGE to 14.9).batteryWarning)
        assertTrue(instant(on, ObdPid.CONTROL_MODULE_VOLTAGE to 12.0).batteryWarning)
        assertFalse("engine off: 12.4 V is normal", instant(ObdPid.RPM to 0.0, ObdPid.CONTROL_MODULE_VOLTAGE to 12.4).batteryWarning)
    }

    @Test fun `instant km per litre`() {
        // 60 km/h / 4 L/h = 15 km/L
        assertEquals(15.0, instant(ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.FUEL_RATE to 4.0, ObdPid.THROTTLE to 30.0).instantKmPerL!!, 1e-9)
        // 120 / 2 = 60 → capped at 30
        assertEquals(30.0, instant(ObdPid.SPEED to 120.0, ObdPid.RPM to 2000.0, ObdPid.FUEL_RATE to 2.0, ObdPid.THROTTLE to 30.0).instantKmPerL!!, 0.0)
        assertEquals("fuel cut = full bar", 30.0, instant(ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 0.0).instantKmPerL!!, 0.0)
        assertEquals("stopped", 0.0, instant(ObdPid.SPEED to 0.0, ObdPid.RPM to 800.0, ObdPid.FUEL_RATE to 0.8).instantKmPerL!!, 0.0)
        assertNull("no fuel data", instant(ObdPid.SPEED to 60.0).instantKmPerL)
    }

    @Test fun `MIL and DTC count from monitor status, raw values passed through`() {
        val snap = TelemetryEngine().onSample(s(ObdPid.BAROMETRIC_PRESSURE to 101.0, monitor = MonitorStatus(milOn = true, dtcCount = 2)))
        assertEquals(true, snap.instant.milOn)
        assertEquals(2, snap.instant.dtcCount)
        assertEquals(101.0, snap.values[ObdPid.BAROMETRIC_PRESSURE]!!, 0.0)
        assertNull(instant().milOn)
    }

    @Test fun `trip km per litre`() {
        assertEquals(12.5, TripTotals(distanceKm = 10.0, fuelLitres = 0.8).economyKmPerL!!, 1e-9)
        assertNull(TripTotals(distanceKm = 0.05, fuelLitres = 0.01).economyKmPerL)
        assertNull(TripTotals(distanceKm = 5.0, fuelLitres = 0.0).economyKmPerL)
    }
}
