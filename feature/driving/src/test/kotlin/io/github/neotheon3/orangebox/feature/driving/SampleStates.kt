package io.github.neotheon3.orangebox.feature.driving

import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TripTotals

/** Kiran's reference shot: coasting in fuel cut at 59 km/h, 1698 rpm. */
object SampleStates {
    val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))

    private val values = mapOf(
        ObdPid.ACCELERATOR_PEDAL_D to 15.0, ObdPid.BAROMETRIC_PRESSURE to 101.0, ObdPid.SPEED to 59.0, ObdPid.RPM to 1698.0,
        ObdPid.ENGINE_LOAD to 42.0, ObdPid.CONTROL_MODULE_VOLTAGE to 14.0, ObdPid.COOLANT_TEMP to 64.0,
        ObdPid.SHORT_FUEL_TRIM_1 to 1.6, ObdPid.LONG_FUEL_TRIM_1 to -3.1, ObdPid.FUEL_PRESSURE to 300.0,
        ObdPid.INTAKE_TEMP to 30.0, ObdPid.AMBIENT_TEMP to 22.0, ObdPid.THROTTLE to 0.0, ObdPid.DISTANCE_WITH_MIL to 0.0,
        ObdPid.OIL_TEMP to 69.0,
    )
    private val active = values.keys + ObdPid.FUEL_LEVEL + ObdPid.O2_B1S1_VOLTAGE

    fun fuelCut(): DashboardUiState = DashboardStateMapper.map(
        connected,
        TelemetrySnapshot(
            0,
            InstantTelemetry(
                speedKph = 59.0, rpm = 1698.0, coolantC = 64.0, fuelLevelPercent = null, voltage = 14.0, fuelFlowLph = 0.0,
                economyLPer100Km = 0.0, accelMps2 = -0.6, fuelCut = true, idling = false, eco = true, overspeed = false,
                warnings = emptySet(), ecoLevel = 1, instantKmPerL = 30.0, milOn = false, dtcCount = 0,
            ),
            TripTotals(durationS = 269.0, distanceKm = 2.04, fuelLitres = 0.229, co2Grams = 529.0, ecoS = 91.0, fuelCutS = 37.0, idleS = 12.0),
            values,
        ),
        active,
    )

    fun overspeed(): DashboardUiState = fuelCut().copy(
        speed = "123", fuelCut = false, overspeed = true, rightGlow = io.github.neotheon3.orangebox.core.dashboardui.PanelGlow.OVERSPEED,
        leftGlow = io.github.neotheon3.orangebox.core.dashboardui.PanelGlow.ECO_YELLOW, ecoLevel = 2, rpm = "3420",
    )
}
