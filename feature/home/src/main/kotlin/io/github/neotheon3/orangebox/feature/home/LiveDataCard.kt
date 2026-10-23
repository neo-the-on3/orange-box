package io.github.neotheon3.orangebox.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.Warning
import java.util.Locale

/** Live decoded values while connected. The full dashboard arrives in M4. */
@Composable
fun LiveDataCard(snapshot: TelemetrySnapshot?) {
    Card(Modifier.fillMaxWidth().testTag("home_live_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium)
            if (snapshot == null) {
                Text(stringResource(R.string.live_waiting), modifier = Modifier.testTag("home_live_waiting"))
                return@Column
            }
            val i = snapshot.instant
            Row("home_live_speed", R.string.live_speed, i.speedKph, "%.0f")
            Row("home_live_rpm", R.string.live_rpm, i.rpm, "%.0f")
            Row("home_live_coolant", R.string.live_coolant, i.coolantC, "%.0f")
            Row("home_live_fuel_level", R.string.live_fuel_level, i.fuelLevelPercent, "%.0f")
            Row("home_live_fuel_flow", R.string.live_fuel_flow, i.fuelFlowLph, "%.2f")
            Row("home_live_economy", R.string.live_economy, i.economyLPer100Km, "%.1f")
            Row("home_live_trip", R.string.live_trip, snapshot.trip.distanceKm, "%.2f")
            if (i.fuelCut) Text(stringResource(R.string.live_fuel_cut), modifier = Modifier.testTag("home_live_fuel_cut"))
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
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("home_live_warning_${w.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun Row(tag: String, label: Int, value: Double?, format: String) {
    val v = value?.let { String.format(Locale.US, format, it) } ?: "–"
    Text(stringResource(label, v), modifier = Modifier.testTag(tag))
}
