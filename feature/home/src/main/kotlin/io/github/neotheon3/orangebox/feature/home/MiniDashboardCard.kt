package io.github.neotheon3.orangebox.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardTheme
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.dashboardui.LampStrip
import io.github.neotheon3.orangebox.core.dashboardui.RpmCluster
import io.github.neotheon3.orangebox.core.dashboardui.SpeedCluster
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.Warning
import java.util.Locale

/** Below this width the two mini clusters stack (issue #97). */
private val StackBelow = 360.dp

/**
 * Home "Live data": a mini dashboard sharing [DashboardUiState] with the driving dashboard, so both show
 * the same values. Always dark (D1). Tapping the card or "Open dashboard" opens the dashboard.
 */
@Composable
fun MiniDashboardCard(
    dashboard: DashboardUiState,
    snapshot: TelemetrySnapshot?,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.home_open_dashboard)
    Card(
        // Tapping anywhere on the card opens the dashboard (#192). A plain tap detector rather than
        // `clickable`, which would merge the clusters' semantics into one node; TalkBack users get the
        // "Open dashboard" button below and the Drive button at the top of Home.
        modifier = modifier.fillMaxWidth().testTag("home_live_card")
            .pointerInput(onOpenDashboard) { detectTapGestures { onOpenDashboard() } },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardColors.Background, contentColor = DashboardColors.Text),
    ) {
        DashboardTheme {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = onOpenDashboard, modifier = Modifier.testTag("home_open_dashboard")) { Text(openLabel) }
                }
                if (snapshot == null) {
                    Text(stringResource(R.string.live_waiting), modifier = Modifier.testTag("home_live_waiting"))
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < StackBelow) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RpmCluster(dashboard, Modifier.fillMaxWidth().height(130.dp), compact = true)
                            SpeedCluster(dashboard, Modifier.fillMaxWidth().height(130.dp), compact = true)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RpmCluster(dashboard, Modifier.weight(1f).height(150.dp), compact = true)
                            SpeedCluster(dashboard, Modifier.weight(1f).height(150.dp), compact = true)
                        }
                    }
                }
                LampStrip(dashboard, Modifier.testTag("home_live_lamps"))
                Text(
                    stringResource(R.string.live_trip_summary, dashboard.tripKmPerL, dashboard.tripKm, dashboard.tripTime, dashboard.economyUnit, dashboard.distanceUnit),
                    modifier = Modifier.testTag("home_live_trip"),
                )
                snapshot?.let { Facts(it) }
            }
        }
    }
}

/** Plain-text secondary readings (coolant, fuel level, fuel flow) and system warnings. */
@Composable
private fun Facts(snapshot: TelemetrySnapshot) {
    val i = snapshot.instant
    fun f(v: Double?, format: String) = v?.let { String.format(Locale.US, format, it) } ?: "–"
    val units = io.github.neotheon3.orangebox.core.designsystem.LocalUnits.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.live_coolant, f(i.coolantC?.let(units::temperature), "%.0f"), units.temperatureLabel), Modifier.testTag("home_live_coolant"), color = DashboardColors.TileTitle)
        Text(stringResource(R.string.live_fuel_level, f(i.fuelLevelPercent, "%.0f")), Modifier.testTag("home_live_fuel_level"), color = DashboardColors.TileTitle)
        Text(stringResource(R.string.live_fuel_flow, f(i.fuelFlowLph, "%.2f")), Modifier.testTag("home_live_fuel_flow"), color = DashboardColors.TileTitle)
    }
    i.warnings.forEach { w ->
        Text(
            stringResource(
                when (w) {
                    Warning.COOLANT_OVERHEAT -> R.string.warn_coolant
                    Warning.LOW_BATTERY -> R.string.warn_low_battery
                    Warning.OVER_VOLTAGE -> R.string.warn_over_voltage
                    Warning.LOW_FUEL -> R.string.warn_low_fuel
                },
            ),
            color = DashboardColors.Warning,
            modifier = Modifier.testTag("home_live_warning_${w.name.lowercase()}"),
        )
    }
}
