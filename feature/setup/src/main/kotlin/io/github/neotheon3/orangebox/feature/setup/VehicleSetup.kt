package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.VehicleRepository
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleCatalogue
import io.github.neotheon3.orangebox.core.model.VehicleFuel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class VehicleSource { CATALOGUE, MANUAL }

data class VehicleForm(
    val id: Long = 0,
    val source: VehicleSource = VehicleSource.CATALOGUE,
    val maker: String? = null,
    val model: String? = null,
    val name: String = "",
    val fuel: VehicleFuel = VehicleFuel.PETROL,
    val displacementCc: String = "",
    val odometerKm: String = "",
)

enum class VehicleFormError { NAME, DISPLACEMENT, ODOMETER, MODEL }

data class VehicleSetupUiState(
    val form: VehicleForm = VehicleForm(),
    val errors: Set<VehicleFormError> = emptySet(),
    val saved: Boolean = false,
)

/**
 * Odometer and engine size accept a decimal part ("12.5", "45210,7"), which is rounded half-up to whole
 * km / cc when saved. The old digits-only filter turned "12.5" into 125 (#123).
 */
object NumberInput {
    private const val MAX_INT_DIGITS = 7
    private const val MAX_FRACTION_DIGITS = 2

    /** Keeps digits and the first '.' or ',' (shown as '.'); drops anything else as it's typed or pasted. */
    fun sanitize(raw: String): String {
        val out = StringBuilder()
        var separator = false
        var intDigits = 0
        var fractionDigits = 0
        for (ch in raw) {
            when {
                ch in '0'..'9' && !separator && intDigits < MAX_INT_DIGITS -> { out.append(ch); intDigits++ }
                ch in '0'..'9' && separator && fractionDigits < MAX_FRACTION_DIGITS -> { out.append(ch); fractionDigits++ }
                (ch == '.' || ch == ',') && !separator -> { separator = true; out.append('.') }
            }
        }
        return out.toString()
    }

    /** "12.5" → 13, "45210.4" → 45210, "7." → 7; null when there is no number. */
    fun parseRounded(text: String): Int? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || t == ".") return null
        return t.toBigDecimalOrNull()?.setScale(0, java.math.RoundingMode.HALF_UP)?.toInt()
    }
}

object VehicleFormValidator {
    fun validate(form: VehicleForm): Pair<Vehicle?, Set<VehicleFormError>> {
        val errors = mutableSetOf<VehicleFormError>()
        val cc = form.displacementCc.trim().takeIf { it.isNotEmpty() }?.let { t ->
            NumberInput.parseRounded(t)?.takeIf { it in Vehicle.DISPLACEMENT_RANGE } ?: null.also { errors += VehicleFormError.DISPLACEMENT }
        }
        val odo = form.odometerKm.trim().takeIf { it.isNotEmpty() }?.let { t ->
            NumberInput.parseRounded(t)?.takeIf { it in 0..Vehicle.ODOMETER_MAX } ?: null.also { errors += VehicleFormError.ODOMETER }
        }
        val vehicle = when (form.source) {
            VehicleSource.CATALOGUE -> {
                val entry = VehicleCatalogue.models.firstOrNull { it.maker == form.maker && it.model == form.model }
                if (entry == null) { errors += VehicleFormError.MODEL; null } else entry.toVehicle(odo).copy(id = form.id)
            }
            VehicleSource.MANUAL -> {
                if (form.name.isBlank()) errors += VehicleFormError.NAME
                if (errors.isEmpty()) Vehicle(form.id, form.name.trim(), null, null, form.fuel, cc, odo) else null
            }
        }
        return if (errors.isEmpty()) vehicle to emptySet() else null to errors
    }

    fun fromVehicle(v: Vehicle): VehicleForm {
        val inCatalogue = VehicleCatalogue.models.any { it.maker == v.maker && it.model == v.model }
        return VehicleForm(
            id = v.id, source = if (inCatalogue) VehicleSource.CATALOGUE else VehicleSource.MANUAL,
            maker = v.maker, model = v.model, name = v.name, fuel = v.fuel,
            displacementCc = v.displacementCc?.toString().orEmpty(), odometerKm = v.odometerKm?.toString().orEmpty(),
        )
    }
}

/** The typed-in form, kept in [SavedStateHandle] so it survives process death (#122). */
internal object VehicleFormState {
    private const val PREFIX = "vehicle_form_"

    fun write(saved: SavedStateHandle, f: VehicleForm) {
        saved[PREFIX + "id"] = f.id
        saved[PREFIX + "source"] = f.source.name
        saved[PREFIX + "maker"] = f.maker
        saved[PREFIX + "model"] = f.model
        saved[PREFIX + "name"] = f.name
        saved[PREFIX + "fuel"] = f.fuel.name
        saved[PREFIX + "cc"] = f.displacementCc
        saved[PREFIX + "odo"] = f.odometerKm
    }

    fun read(saved: SavedStateHandle): VehicleForm? {
        if (!saved.contains(PREFIX + "source")) return null
        val d = VehicleForm()
        return VehicleForm(
            id = saved.get<Long>(PREFIX + "id") ?: d.id,
            source = VehicleSource.entries.firstOrNull { it.name == saved.get<String>(PREFIX + "source") } ?: d.source,
            maker = saved[PREFIX + "maker"],
            model = saved[PREFIX + "model"],
            name = saved.get<String>(PREFIX + "name") ?: d.name,
            fuel = VehicleFuel.entries.firstOrNull { it.name == saved.get<String>(PREFIX + "fuel") } ?: d.fuel,
            displacementCc = saved.get<String>(PREFIX + "cc") ?: d.displacementCc,
            odometerKm = saved.get<String>(PREFIX + "odo") ?: d.odometerKm,
        )
    }
}

@HiltViewModel
class VehicleSetupViewModel @Inject constructor(
    private val vehicles: VehicleRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleSetupUiState(form = VehicleFormState.read(saved) ?: VehicleForm()))
    val state: StateFlow<VehicleSetupUiState> = _state.asStateFlow()

    /**
     * Set when opened from Settings → Vehicles (#61): 0 adds a new car, any other id edits that car.
     * Null (onboarding, the setup shortcut) edits the active vehicle.
     */
    val editId: Long? = saved.get<Long>(VehicleEditDestination.ARG)

    init {
        // A restored, half-typed form wins over the stored vehicle.
        if (VehicleFormState.read(saved) == null && editId != 0L) {
            viewModelScope.launch {
                val v = if (editId == null) vehicles.activeVehicle.first() else vehicles.vehicles.first().firstOrNull { it.id == editId }
                v?.let { _state.update { s -> s.copy(form = VehicleFormValidator.fromVehicle(it)) } }
            }
        }
    }

    fun onFormChange(form: VehicleForm) {
        VehicleFormState.write(saved, form)
        _state.update { it.copy(form = form, errors = emptySet(), saved = false) }
    }

    /** Validates and stores the vehicle; activates it when editing the active one or there is none yet. */
    fun save() {
        val (vehicle, errors) = VehicleFormValidator.validate(_state.value.form)
        if (vehicle == null) { _state.update { it.copy(errors = errors) }; return }
        viewModelScope.launch {
            // From the Vehicles list, saving a car doesn't switch the active one, unless there was none.
            val activate = editId == null || vehicles.activeVehicle.first() == null
            val id = vehicles.save(vehicle)
            if (activate) vehicles.setActive(id)
            _state.update { it.copy(form = it.form.copy(id = id), saved = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleSetupContent(state: VehicleSetupUiState, onFormChange: (VehicleForm) -> Unit, modifier: Modifier = Modifier) {
    val form = state.form
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            VehicleSource.entries.forEachIndexed { i, src ->
                SegmentedButton(
                    selected = form.source == src,
                    onClick = { onFormChange(form.copy(source = src)) },
                    shape = SegmentedButtonDefaults.itemShape(i, VehicleSource.entries.size),
                    modifier = Modifier.testTag("vehicle_source_${src.name.lowercase()}"),
                ) {
                    Text(stringResource(if (src == VehicleSource.CATALOGUE) R.string.vehicle_from_list else R.string.vehicle_manual))
                }
            }
        }
        if (form.source == VehicleSource.CATALOGUE) {
            Dropdown(
                label = stringResource(R.string.vehicle_maker),
                value = form.maker.orEmpty(),
                options = VehicleCatalogue.makers,
                onSelect = { onFormChange(form.copy(maker = it, model = null)) },
                tag = "vehicle_maker",
            )
            Dropdown(
                label = stringResource(R.string.vehicle_model),
                value = form.model.orEmpty(),
                options = form.maker?.let { m -> VehicleCatalogue.modelsOf(m).map { it.model } }.orEmpty(),
                onSelect = { onFormChange(form.copy(model = it)) },
                tag = "vehicle_model",
                error = if (VehicleFormError.MODEL in state.errors) stringResource(R.string.vehicle_error_model) else null,
                enabled = form.maker != null,
            )
        } else {
            OutlinedTextField(
                value = form.name, onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text(stringResource(R.string.vehicle_name)) }, singleLine = true,
                isError = VehicleFormError.NAME in state.errors,
                supportingText = if (VehicleFormError.NAME in state.errors) { { Text(stringResource(R.string.vehicle_error_name)) } } else null,
                modifier = Modifier.fillMaxWidth().testTag("vehicle_name"),
            )
            Text(stringResource(R.string.vehicle_fuel), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                VehicleFuel.entries.forEach { fuel ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = form.fuel == fuel, onClick = { onFormChange(form.copy(fuel = fuel)) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp)
                            .testTag("vehicle_fuel_${fuel.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = form.fuel == fuel, onClick = null)
                        Text(stringResource(fuel.labelRes()), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            NumberField(
                stringResource(R.string.vehicle_displacement), form.displacementCc, { onFormChange(form.copy(displacementCc = it)) },
                "vehicle_displacement", if (VehicleFormError.DISPLACEMENT in state.errors) stringResource(R.string.vehicle_error_displacement) else null,
            )
        }
        NumberField(
            stringResource(R.string.vehicle_odometer), form.odometerKm, { onFormChange(form.copy(odometerKm = it)) },
            "vehicle_odometer", if (VehicleFormError.ODOMETER in state.errors) stringResource(R.string.vehicle_error_odometer) else null,
        )
        if (state.saved) Text(stringResource(R.string.vehicle_saved), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("vehicle_saved"))
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, tag: String, error: String?) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(NumberInput.sanitize(v)) },
        label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit, tag: String, error: String? = null, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, isError = error != null, supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                    modifier = Modifier.testTag("${tag}_option_$option"),
                )
            }
        }
    }
}

internal fun VehicleFuel.labelRes() = when (this) {
    VehicleFuel.PETROL -> R.string.fuel_petrol
    VehicleFuel.DIESEL -> R.string.fuel_diesel
    VehicleFuel.LPG -> R.string.fuel_lpg
    VehicleFuel.CNG -> R.string.fuel_cng
    VehicleFuel.ETHANOL -> R.string.fuel_ethanol
}
