package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
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
    Cluster(
        glow = state.leftGlow, tag = "rpm", modifier = modifier, compact = compact, meterMax = meterMax,
        header = { HeaderLamp(DashboardIcons.FuelDrop, stringResource(R.string.dashboard_fuel_cut), LampState(state.fuelCut, DashboardColors.FuelCut)) },
        value = state.rpm, unit = stringResource(R.string.dashboard_unit_rpm),
        spoken = stringResource(R.string.dashboard_spoken_rpm, state.rpm),
    ) {
        Lamp(DashboardIcons.Coolant, stringResource(R.string.dashboard_coolant), indicator(state.coolant), Modifier.weight(1f))
        Lamp(DashboardIcons.Battery, stringResource(R.string.dashboard_battery), indicator(state.battery), Modifier.weight(1f))
        Lamp(DashboardIcons.EcoLeaf, stringResource(R.string.dashboard_eco), eco(state.ecoLevel), Modifier.weight(1f))
    }
}

/** Right cluster: OVERSPEED header lamp, speed meter, ACCEL. / BRAKE / IDLING lamps. */
@Composable
fun SpeedCluster(state: DashboardUiState, modifier: Modifier = Modifier, meterMax: TextUnit = DashboardType.MeterMax, compact: Boolean = false) {
    Cluster(
        glow = state.rightGlow, tag = "speed", modifier = modifier, compact = compact, meterMax = meterMax,
        header = { HeaderLamp(DashboardIcons.OverspeedGauge, stringResource(R.string.dashboard_overspeed), LampState(state.overspeed, DashboardColors.Overspeed)) },
        value = state.speed, unit = state.speedUnit,
        spoken = stringResource(R.string.dashboard_spoken_speed, state.speed, state.speedUnit),
    ) {
        Lamp(DashboardIcons.AccelCar, stringResource(R.string.dashboard_accel), event(state.harshAccel), Modifier.weight(1f))
        Lamp(DashboardIcons.BrakeCar, stringResource(R.string.dashboard_brake), event(state.harshBrake), Modifier.weight(1f))
        Lamp(DashboardIcons.Idling, stringResource(R.string.dashboard_idling), event(state.idling), Modifier.weight(1f))
    }
}

/**
 * Shared cluster layout. Square-ish panels (landscape) stack header / meter / lamps like the legacy
 * cluster; wide, short panels (portrait, ≥ 1.6:1) put the lamps bottom-left and the meter on the right
 * so the number keeps the full height.
 */
@Composable
private fun Cluster(
    glow: PanelGlow,
    tag: String,
    modifier: Modifier,
    compact: Boolean,
    meterMax: TextUnit,
    header: @Composable () -> Unit,
    value: String,
    unit: String,
    spoken: String,
    lamps: @Composable RowScope.() -> Unit,
) {
    ClusterPanel(glow, modifier.testTag("cluster_$tag"), contentPadding = if (compact) 8.dp else 12.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.6f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                header()
                if (wide && !compact) {
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom) {
                        Row(Modifier.weight(1f), content = lamps)
                        MeterText(value, unit, spoken, Modifier.weight(1.1f).fillMaxHeight().testTag("meter_$tag"), max = meterMax)
                    }
                } else {
                    MeterText(value, unit, spoken, Modifier.fillMaxWidth().weight(1f).testTag("meter_$tag"), max = meterMax)
                    if (!compact) Row(Modifier.fillMaxWidth(), content = lamps)
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
    val mil = stringResource(R.string.dashboard_mil)
    val obd = stringResource(R.string.dashboard_obd)
    val obdState = LampState(state.obdOn, DashboardColors.Obd)
    val m = modifier
        .background(DashboardColors.CardFill, shape)
        .border(1.dp, DashboardColors.CardRim, shape)
        .padding(4.dp)
        .testTag("connect_chips")
    if (vertical) {
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Chip(DashboardIcons.MilEngine, mil, milState, Modifier.fillMaxWidth())
            Chip(DashboardIcons.ObdPlug, obd, obdState, Modifier.fillMaxWidth())
        }
    } else {
        Row(m, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(DashboardIcons.MilEngine, mil, milState, Modifier.weight(1f))
            Chip(DashboardIcons.ObdPlug, obd, obdState, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, state: LampState, modifier: Modifier) {
    val spoken = state.stateLabel ?: stringResource(if (state.on) R.string.dashboard_state_on else R.string.dashboard_state_off)
    val tint = if (state.on) state.color else DashboardColors.LampOff
    Column(
        modifier
            .heightIn(min = 40.dp)
            .border(1.dp, tint.copy(alpha = if (state.on) 0.9f else 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
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

/**
 * FUEL card: trip km/L, instant bar 0–30, litres used. Value rows share the card height; the bar's
 * tick labels are dropped when the card is too short for them (small landscape phones, large fonts).
 */
@Composable
fun FuelCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("card_fuel")) {
        val fontScale = LocalDensity.current.fontScale
        val showTicks = maxHeight == Dp.Infinity || maxHeight >= 112.dp * fontScale
        CenterCard(stringResource(R.string.dashboard_fuel), DashboardIcons.FuelPump, Modifier.fillMaxWidth().then(if (maxHeight == Dp.Infinity) Modifier else Modifier.fillMaxHeight())) {
            ValueWithUnit(
                state.tripKmPerL, state.economyUnit,
                stringResource(R.string.dashboard_spoken_economy, state.tripKmPerL, state.instantKmPerL, state.economyUnit),
                Modifier.weight(1f),
            )
            FuelBar(state.instantFraction, Modifier.padding(vertical = 2.dp), showTicks = showTicks)
            ValueWithUnit(
                state.fuelUsedL, stringResource(R.string.dashboard_unit_l),
                stringResource(R.string.dashboard_spoken_fuel_used, state.fuelUsedL), Modifier.weight(1f),
            )
        }
    }
}

/** TRIP card: distance and elapsed time. */
@Composable
fun TripCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.dashboard_spoken_trip, state.tripKm, state.tripTime, state.distanceUnit)
    CenterCard(stringResource(R.string.dashboard_trip), DashboardIcons.TripDoc, modifier.testTag("card_trip")) {
        Column(Modifier.fillMaxWidth().weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
            ValueWithUnit(state.tripKm, state.distanceUnit, spoken, Modifier.weight(1f))
            ValueWithUnit(state.tripTime, "", spoken, Modifier.weight(1f))
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
