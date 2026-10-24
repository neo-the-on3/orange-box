package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spoken state for a lamp, e.g. "warning" / "on" / "off". */
@Composable
private fun indicator(level: IndicatorLevel): LampState = when (level) {
    IndicatorLevel.OFF -> LampState(false, DashboardColors.LampOff, stringResource(R.string.dashboard_no_data))
    IndicatorLevel.NORMAL -> LampState(true, DashboardColors.Normal, stringResource(R.string.dashboard_state_on))
    IndicatorLevel.WARNING -> LampState(true, DashboardColors.Warning, stringResource(R.string.dashboard_state_warning))
}

@Composable
private fun eco(level: Int): LampState = when (level) {
    0 -> LampState(false, DashboardColors.LampOff)
    1 -> LampState(true, DashboardColors.EcoGreen, "green")
    2, 3 -> LampState(true, DashboardColors.EcoYellow, "yellow")
    else -> LampState(true, DashboardColors.EcoRed, "red")
}

@Composable
private fun event(on: Boolean) =
    LampState(on, DashboardColors.Event, stringResource(if (on) R.string.dashboard_state_active else R.string.dashboard_state_off))

/** Left cluster: FUEL-CUT header lamp, RPM meter, COOLANT / BATTERY / ECO lamps. */
@Composable
fun RpmCluster(state: DashboardUiState, modifier: Modifier = Modifier, meterMax: TextUnit = DashboardType.MeterMax, compact: Boolean = false) {
    ClusterPanel(state.leftGlow, modifier.testTag("cluster_rpm"), contentPadding = if (compact) 8.dp else 14.dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            HeaderLamp(
                DashboardIcons.FuelDrop, stringResource(R.string.dashboard_fuel_cut),
                LampState(state.fuelCut, DashboardColors.FuelCut),
            )
            MeterText(
                state.rpm, stringResource(R.string.dashboard_unit_rpm),
                stringResource(R.string.dashboard_spoken_rpm, state.rpm),
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("meter_rpm"), max = meterMax,
            )
            if (!compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Lamp(DashboardIcons.Coolant, stringResource(R.string.dashboard_coolant), indicator(state.coolant))
                    Lamp(DashboardIcons.Battery, stringResource(R.string.dashboard_battery), indicator(state.battery))
                    Lamp(DashboardIcons.EcoLeaf, stringResource(R.string.dashboard_eco), eco(state.ecoLevel))
                }
            }
        }
    }
}

/** Right cluster: OVERSPEED header lamp, speed meter, ACCEL. / BRAKE / IDLING lamps. */
@Composable
fun SpeedCluster(state: DashboardUiState, modifier: Modifier = Modifier, meterMax: TextUnit = DashboardType.MeterMax, compact: Boolean = false) {
    ClusterPanel(state.rightGlow, modifier.testTag("cluster_speed"), contentPadding = if (compact) 8.dp else 14.dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            HeaderLamp(
                DashboardIcons.OverspeedGauge, stringResource(R.string.dashboard_overspeed),
                LampState(state.overspeed, DashboardColors.Overspeed),
            )
            MeterText(
                state.speed, stringResource(R.string.dashboard_unit_kmh),
                stringResource(R.string.dashboard_spoken_speed, state.speed),
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("meter_speed"), max = meterMax,
            )
            if (!compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Lamp(DashboardIcons.AccelCar, stringResource(R.string.dashboard_accel), event(state.harshAccel))
                    Lamp(DashboardIcons.BrakeCar, stringResource(R.string.dashboard_brake), event(state.harshBrake))
                    Lamp(DashboardIcons.Idling, stringResource(R.string.dashboard_idling), event(state.idling))
                }
            }
        }
    }
}

/** MIL + OBD chips (centre top). */
@Composable
fun ConnectChips(state: DashboardUiState, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val shape = RoundedCornerShape(3.dp)
    val milState = if (state.milOn) {
        LampState(true, DashboardColors.Mil, state.dtcCount?.let { stringResource(R.string.dashboard_mil_dtcs, it) })
    } else {
        LampState(false, DashboardColors.LampOff)
    }
    val chips: @Composable () -> Unit = {
        Chip(DashboardIcons.MilEngine, stringResource(R.string.dashboard_mil), milState)
        Chip(DashboardIcons.ObdPlug, stringResource(R.string.dashboard_obd), LampState(state.obdOn, DashboardColors.Obd))
    }
    val m = modifier
        .background(DashboardColors.CardFill, shape)
        .border(1.dp, DashboardColors.CardRim, shape)
        .padding(4.dp)
        .testTag("connect_chips")
    if (vertical) {
        Column(m, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { chips() }
    } else {
        Row(m, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { chips() }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, state: LampState) {
    val spoken = state.stateLabel ?: stringResource(if (state.on) R.string.dashboard_state_on else R.string.dashboard_state_off)
    val tint = if (state.on) state.color else DashboardColors.LampOff
    Column(
        Modifier
            .heightIn(min = 40.dp)
            .border(1.dp, tint.copy(alpha = if (state.on) 0.9f else 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "$label, $spoken" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label, color = tint, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, softWrap = false,
            fontWeight = if (state.on) FontWeight.Black else FontWeight.Bold,
        )
    }
}

/** FUEL card: trip km/L, instant bar 0–30, litres used. */
@Composable
fun FuelCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    CenterCard(stringResource(R.string.dashboard_fuel), DashboardIcons.FuelPump, modifier.testTag("card_fuel")) {
        ValueWithUnit(
            state.tripKmPerL, stringResource(R.string.dashboard_unit_kml),
            stringResource(R.string.dashboard_spoken_economy, state.tripKmPerL, state.instantKmPerL),
        )
        FuelBar(state.instantFraction, Modifier.padding(vertical = 3.dp))
        ValueWithUnit(state.fuelUsedL, stringResource(R.string.dashboard_unit_l), stringResource(R.string.dashboard_spoken_fuel_used, state.fuelUsedL))
    }
}

/** TRIP card: distance and elapsed time. */
@Composable
fun TripCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.dashboard_spoken_trip, state.tripKm, state.tripTime)
    CenterCard(stringResource(R.string.dashboard_trip), DashboardIcons.TripDoc, modifier.testTag("card_trip")) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
            ValueWithUnit(state.tripKm, stringResource(R.string.dashboard_unit_km), spoken)
            ValueWithUnit(state.tripTime, "", spoken)
        }
    }
}

/** One-line lamp strip for the Home mini-dashboard: MIL, OBD, Coolant, Battery, Eco, Accel, Brake, Idle. */
@Composable
fun LampStrip(state: DashboardUiState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val small = 20.dp
        Lamp(DashboardIcons.MilEngine, stringResource(R.string.dashboard_mil), LampState(state.milOn, DashboardColors.Mil), iconSize = small)
        Lamp(DashboardIcons.ObdPlug, stringResource(R.string.dashboard_obd), LampState(state.obdOn, DashboardColors.Obd), iconSize = small)
        Lamp(DashboardIcons.Coolant, stringResource(R.string.dashboard_coolant), indicator(state.coolant), iconSize = small)
        Lamp(DashboardIcons.Battery, stringResource(R.string.dashboard_battery), indicator(state.battery), iconSize = small)
        Lamp(DashboardIcons.EcoLeaf, stringResource(R.string.dashboard_eco), eco(state.ecoLevel), iconSize = small)
        Lamp(DashboardIcons.AccelCar, stringResource(R.string.dashboard_accel), event(state.harshAccel), iconSize = small)
        Lamp(DashboardIcons.BrakeCar, stringResource(R.string.dashboard_brake), event(state.harshBrake), iconSize = small)
        Lamp(DashboardIcons.Idling, stringResource(R.string.dashboard_idling), event(state.idling), iconSize = small)
    }
}
