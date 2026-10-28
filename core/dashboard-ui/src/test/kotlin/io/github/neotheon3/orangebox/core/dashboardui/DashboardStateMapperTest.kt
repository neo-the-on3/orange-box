package io.github.neotheon3.orangebox.core.dashboardui

import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TripTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStateMapperTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 12.6))

    private fun instant(
        speed: Double? = 59.0, rpm: Double? = 1698.0, fuelCut: Boolean = false, overspeed: Boolean = false,
        harshAccel: Boolean = false, harshBrake: Boolean = false, eco: Int = 1, ecoRule: Boolean = true, coolantWarning: Boolean = false,
        batteryWarning: Boolean = false, kmPerL: Double? = 8.9, mil: Boolean? = false, dtc: Int? = 0,
    ) = InstantTelemetry(
        speedKph = speed, rpm = rpm, coolantC = 64.0, fuelLevelPercent = null, voltage = 14.0, fuelFlowLph = 2.0,
        economyLPer100Km = null, accelMps2 = null, fuelCut = fuelCut, idling = false, eco = ecoRule, overspeed = overspeed,
        warnings = emptySet(), harshAccel = harshAccel, harshBrake = harshBrake, ecoLevel = eco,
        coolantWarning = coolantWarning, batteryWarning = batteryWarning, instantKmPerL = kmPerL, milOn = mil, dtcCount = dtc,
    )

    private val trip = TripTotals(durationS = 269.4, distanceKm = 2.04, fuelLitres = 0.229, co2Grams = 529.0, ecoS = 91.0)

    private fun snap(i: InstantTelemetry = instant(), values: Map<ObdPid, Double> = mapOf(ObdPid.RPM to 1698.0)) =
        TelemetrySnapshot(0, i, trip, values)

    @Test fun `no connection or no data shows placeholders and idle panels`() {
        val idle = DashboardStateMapper.map(ObdConnectionState.Idle, null)
        assertEquals(DashboardUiState(), idle)
        assertEquals("---", idle.rpm); assertEquals("---", idle.speed)
        assertEquals("--.-", idle.tripKmPerL); assertEquals("--.-", idle.tripKm); assertEquals("-:--:--", idle.tripTime)
        assertFalse(idle.obdOn)
        assertEquals(PanelGlow.NONE, idle.leftGlow)
        assertTrue(idle.detailPages.flatten().all { it.status == TileStatus.NO_DATA && it.value == "---" || it.value == "--.-" })
        assertEquals("link lost drops live data", DashboardUiState(), DashboardStateMapper.map(ObdConnectionState.LinkLost, snap()))
        assertTrue("connected, waiting for first sample", DashboardStateMapper.map(connected, null).obdOn)
    }

    @Test fun `live values are formatted like the legacy cluster`() {
        val s = DashboardStateMapper.map(connected, snap())
        assertTrue(s.live); assertTrue(s.obdOn)
        assertEquals("1698", s.rpm); assertEquals("59", s.speed)
        // 2.04 km / 0.229 L = 8.908 km/L
        assertEquals("8.9", s.tripKmPerL)
        assertEquals("8.9", s.instantKmPerL)
        assertEquals(8.9f / 30f, s.instantFraction, 1e-4f)
        assertEquals("0.2", s.fuelUsedL)
        assertEquals("2.0", s.tripKm)
        assertEquals("0:04:29", s.tripTime)
        assertEquals(IndicatorLevel.NORMAL, s.coolant)
        assertEquals(IndicatorLevel.NORMAL, s.battery)
    }

    @Test fun `speedometer offset only changes the shown speed (#210)`() {
        val plain = DashboardStateMapper.map(connected, snap())
        val offset = DashboardStateMapper.map(connected, snap(), speedometerOffsetPercent = 5)
        assertEquals("59", plain.speed)
        assertEquals("62", offset.speed) // 59.x × 1.05
        assertEquals(plain.overspeed, offset.overspeed)
        assertEquals(plain.tripKm, offset.tripKm)
    }

    @Test fun `left glow - legacy mapping, fuel cut has no tint of its own (#198)`() {
        assertEquals(PanelGlow.ECO_GREEN, DashboardStateMapper.leftGlow(1))
        assertEquals(PanelGlow.ECO_YELLOW, DashboardStateMapper.leftGlow(2))
        assertEquals(PanelGlow.ECO_YELLOW, DashboardStateMapper.leftGlow(3))
        assertEquals(PanelGlow.ECO_RED, DashboardStateMapper.leftGlow(4))
        assertEquals(PanelGlow.ECO_RED, DashboardStateMapper.leftGlow(5))
        assertEquals(PanelGlow.NONE, DashboardStateMapper.leftGlow(0))
        // Legacy: the FUEL-CUT lamp lights, the panel keeps its load colour (low load on overrun: green).
        val cut = DashboardStateMapper.map(connected, snap(instant(fuelCut = true, eco = 1)))
        assertEquals(PanelGlow.ECO_GREEN, cut.leftGlow)
        assertTrue(cut.fuelCut)
    }

    @Test fun `eco lamp is off and the panel follows load at 130 kmh and 3510 rpm while overspeeding (#138, #198)`() {
        // Simulator cruise: light load (level 1) but rpm above 2500, so the eco rule is false and no eco time is counted.
        val s = DashboardStateMapper.map(connected, snap(instant(speed = 130.0, rpm = 3510.0, overspeed = true, eco = 1, ecoRule = false)))
        assertEquals(0, s.ecoLevel)
        assertEquals("light load is green, as in CarooLive (#198)", PanelGlow.ECO_GREEN, s.leftGlow)
        assertFalse(s.fuelCut)
        assertEquals(PanelGlow.OVERSPEED, s.rightGlow)
    }

    @Test fun `engine to dashboard - 130 kmh cruise at 3510 rpm and 40 pct load counts no eco and shows no eco lamp`() {
        val engine = io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine()
        var snapshot: TelemetrySnapshot? = null
        for (k in 0..10) {
            snapshot = engine.onSample(
                io.github.neotheon3.orangebox.core.obd.pid.VehicleSample(
                    k * 1_000L, mapOf(ObdPid.SPEED to 130.0, ObdPid.RPM to 3510.0, ObdPid.ENGINE_LOAD to 40.0),
                ),
            )
        }
        val snap = snapshot!!
        assertTrue(snap.instant.overspeed)
        assertFalse(snap.instant.eco)
        assertEquals(0.0, snap.trip.ecoS, 0.0)
        assertEquals("load alone would be green", 1, snap.instant.ecoLevel)
        val s = DashboardStateMapper.map(connected, snap)
        assertEquals(0, s.ecoLevel)
        assertEquals("tinted by load, never blank while moving (#138, #198)", PanelGlow.ECO_GREEN, s.leftGlow)
        assertEquals(PanelGlow.OVERSPEED, s.rightGlow)
    }

    @Test fun `eco lamp follows the eco rule, load only shades it`() {
        assertEquals(1, DashboardStateMapper.map(connected, snap(instant(eco = 1, ecoRule = true))).ecoLevel)
        assertEquals(1, DashboardStateMapper.map(connected, snap(instant(eco = 0, ecoRule = true))).ecoLevel) // no load PID
        assertEquals(2, DashboardStateMapper.map(connected, snap(instant(eco = 3, ecoRule = true))).ecoLevel)
        assertEquals(2, DashboardStateMapper.map(connected, snap(instant(eco = 4, ecoRule = true))).ecoLevel)
        for (level in 0..4) assertEquals(0, DashboardStateMapper.map(connected, snap(instant(eco = level, ecoRule = false))).ecoLevel)
        assertEquals(PanelGlow.ECO_GREEN, DashboardStateMapper.map(connected, snap(instant(eco = 1))).leftGlow)
    }

    @Test fun `RPM panel follows the legacy load level, not the eco rule (#198)`() {
        assertEquals(PanelGlow.ECO_GREEN, DashboardStateMapper.map(connected, snap(instant(eco = 1))).leftGlow)
        assertEquals(PanelGlow.ECO_YELLOW, DashboardStateMapper.map(connected, snap(instant(eco = 2))).leftGlow)
        assertEquals(PanelGlow.ECO_YELLOW, DashboardStateMapper.map(connected, snap(instant(eco = 3))).leftGlow)
        assertEquals(PanelGlow.ECO_RED, DashboardStateMapper.map(connected, snap(instant(eco = 4))).leftGlow)
        // Not eco (hard driving) still tints: this used to drop to NONE ("blank panel").
        for (level in 1..4) assertTrue(DashboardStateMapper.map(connected, snap(instant(eco = level, ecoRule = false))).leftGlow != PanelGlow.NONE)
        assertEquals("stopped / no data", PanelGlow.NONE, DashboardStateMapper.map(connected, snap(instant(eco = 0))).leftGlow)
    }

    @Test fun `trip and fuel tiles use the recorded trip totals carried over a reconnect (#144)`() {
        // Engine restarted its totals at the reconnect (2:53 so far); the recorded trip is 10:53 long.
        val engineTotals = TripTotals(durationS = 173.0, distanceKm = 0.0, fuelLitres = 0.01, ecoS = 0.0)
        val recorded = TripTotals(durationS = 653.0, distanceKm = 4.26, fuelLitres = 0.32, ecoS = 120.0)
        val snapshot = TelemetrySnapshot(0, instant(), engineTotals, mapOf(ObdPid.RPM to 1698.0))
        val s = DashboardStateMapper.map(connected, snapshot, recordedTrip = recorded)
        assertEquals("0:10:53", s.tripTime)
        assertEquals("4.3", s.tripKm)
        assertEquals("0.3", s.fuelUsedL)
        assertTrue(s.detailPages.flatten().any { it.value == "0:02:00" }) // eco time from the recorded trip
        assertEquals("no trip recording: engine totals", "0:02:53", DashboardStateMapper.map(connected, snapshot).tripTime)
    }

    @Test fun `US units convert speed, distance, economy and temperatures for display only (#58)`() {
        val us = io.github.neotheon3.orangebox.core.model.UnitPreferences.forCountry("US")
        val s = DashboardStateMapper.map(connected, snap(values = mapOf(ObdPid.RPM to 1698.0, ObdPid.COOLANT_TEMP to 64.0, ObdPid.SPEED to 59.0)), units = us)
        assertEquals("mph", s.speedUnit); assertEquals("mi", s.distanceUnit); assertEquals("mpg", s.economyUnit)
        assertEquals("59 km/h = 36.7 mph", "37", s.speed)
        assertEquals("2.04 km = 1.27 mi", "1.3", s.tripKm)
        assertEquals("8.908 km/L = 20.95 mpg US", "21.0", s.tripKmPerL)
        val tiles = s.detailPages.flatten()
        assertTrue("64 °C coolant = 147 °F: ${tiles.map { it.value }}", tiles.any { it.value.startsWith("147") })
        assertEquals("warnings still use the metric values", IndicatorLevel.NORMAL, s.coolant)
    }

    @Test fun `right glow - overspeed beats harsh beats none`() {
        assertEquals(PanelGlow.OVERSPEED, DashboardStateMapper.map(connected, snap(instant(overspeed = true, harshBrake = true))).rightGlow)
        assertEquals(PanelGlow.HARSH, DashboardStateMapper.map(connected, snap(instant(harshAccel = true))).rightGlow)
        assertEquals(PanelGlow.HARSH, DashboardStateMapper.map(connected, snap(instant(harshBrake = true))).rightGlow)
        assertEquals(PanelGlow.NONE, DashboardStateMapper.map(connected, snap()).rightGlow)
    }

    @Test fun `warnings and MIL`() {
        val s = DashboardStateMapper.map(connected, snap(instant(coolantWarning = true, batteryWarning = true, mil = true, dtc = 2)))
        assertEquals(IndicatorLevel.WARNING, s.coolant)
        assertEquals(IndicatorLevel.WARNING, s.battery)
        assertTrue(s.milOn); assertEquals(2, s.dtcCount)
    }

    @Test fun `detail grid - 3 pages of 9, warnings glow red, unsupported vs not read yet`() {
        val values = mapOf(
            ObdPid.CONTROL_MODULE_VOLTAGE to 12.0, ObdPid.COOLANT_TEMP to 105.0, ObdPid.SPEED to 0.0, ObdPid.RPM to 791.0,
            ObdPid.BAROMETRIC_PRESSURE to 101.0,
        )
        val active = setOf(ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.COOLANT_TEMP, ObdPid.SPEED, ObdPid.RPM, ObdPid.BAROMETRIC_PRESSURE, ObdPid.FUEL_LEVEL)
        val pages = DashboardStateMapper.map(
            connected, snap(instant(coolantWarning = true, batteryWarning = true, dtc = 2), values), active,
        ).detailPages
        assertEquals(listOf(9, 9, 9), pages.map { it.size })
        val p1 = pages[0]
        assertEquals(R.string.detail_accel_pedal, p1[0].title)
        assertEquals(TileStatus.UNSUPPORTED, p1[0].status); assertEquals("---", p1[0].value)
        assertEquals("not read yet", TileStatus.NO_DATA, p1[1].status)
        assertEquals("101", p1[2].value)
        assertEquals("791", p1[4].value)
        assertEquals(TileStatus.WARNING, p1[6].status); assertEquals("12.0", p1[6].value); assertTrue(p1[6].large)
        assertEquals(TileStatus.WARNING, p1[7].status); assertEquals("105.0", p1[7].value)
        assertEquals(TileStatus.WARNING, p1[8].status); assertEquals("2", p1[8].value)
        val p3 = pages[2]
        assertEquals("8.9", p3[0].value)
        assertEquals("0.53", p3[2].value) // 529 g CO2
        assertEquals("0:01:31", p3[6].value)
        assertTrue(p3.drop(6).all { it.large && it.status == TileStatus.NORMAL })
    }

    @Test fun `normal large tiles glow blue`() {
        val pages = DashboardStateMapper.map(connected, snap(values = mapOf(ObdPid.CONTROL_MODULE_VOLTAGE to 14.0, ObdPid.COOLANT_TEMP to 64.0))).detailPages
        assertEquals(TileStatus.NORMAL, pages[0][6].status)
        assertEquals(TileStatus.NORMAL, pages[0][7].status)
        assertEquals("64.0", pages[0][7].value)
        assertEquals("0 DTCs", TileStatus.NORMAL, pages[0][8].status)
    }

    @Test fun `elapsed formatting`() {
        assertEquals("0:00:00", DashboardStateMapper.elapsed(0.0))
        assertEquals("1:01:01", DashboardStateMapper.elapsed(3661.9))
        assertEquals("-:--:--", DashboardStateMapper.elapsed(null))
    }
}
