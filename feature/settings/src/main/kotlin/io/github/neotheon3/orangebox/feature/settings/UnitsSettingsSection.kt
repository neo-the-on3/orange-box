package io.github.neotheon3.orangebox.feature.settings

import io.github.neotheon3.orangebox.core.designsystem.SegmentLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.model.DistanceUnit
import io.github.neotheon3.orangebox.core.model.EconomyUnit
import io.github.neotheon3.orangebox.core.model.PetrolBlend
import io.github.neotheon3.orangebox.core.model.TemperatureUnit
import io.github.neotheon3.orangebox.core.model.UnitPreferences

/**
 * "Units" (#58): distance and speed, fuel economy, temperature. Applies app-wide at once. Also the petrol blend
 * (#208), which the fuel estimate needs: E20 burns about 7.5 % more litres than E0 for the same air.
 */
@Composable
fun UnitsSettingsSection(
    units: UnitPreferences,
    petrolBlend: PetrolBlend = PetrolBlend.E0,
    onPetrolBlendChanged: (PetrolBlend) -> Unit = {},
    onChanged: (UnitPreferences) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_units_section), style = MaterialTheme.typography.titleMedium)
        Choice(
            stringResource(R.string.settings_units_distance), DistanceUnit.entries, units.distance,
            label = { "${it.distanceLabel}, ${it.speedLabel}" }, tag = "settings_units_distance",
        ) { onChanged(units.copy(distance = it)) }
        Choice(
            stringResource(R.string.settings_units_economy), EconomyUnit.entries, units.economy,
            label = { it.label.replace(" (", " ").removeSuffix(")") }, tag = "settings_units_economy",
        ) { onChanged(units.copy(economy = it)) }
        Choice(
            stringResource(R.string.settings_units_temperature), TemperatureUnit.entries, units.temperature,
            label = { it.label }, tag = "settings_units_temperature",
        ) { onChanged(units.copy(temperature = it)) }
        Choice(
            stringResource(R.string.settings_petrol_blend), PetrolBlend.entries, petrolBlend,
            label = { it.name }, tag = "settings_petrol_blend",
        ) { onPetrolBlendChanged(it) }
        Text(
            stringResource(R.string.settings_petrol_blend_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T : Enum<T>> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.testTag("${tag}_${option.name.lowercase()}"),
                ) { SegmentLabel(label(option), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
