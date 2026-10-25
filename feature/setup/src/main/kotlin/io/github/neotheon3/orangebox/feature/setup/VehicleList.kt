package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.VehicleRepository
import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Settings → Vehicles (M8 #61): every stored car, which one is active, add / edit / delete. */
@Serializable data object VehicleListDestination

/** The vehicle form for one car: [vehicleId] 0 adds a new one. */
@Serializable data class VehicleEditDestination(val vehicleId: Long) {
    companion object {
        /** The [androidx.lifecycle.SavedStateHandle] key navigation puts [vehicleId] under. */
        const val ARG = "vehicleId"
    }
}

data class VehicleListUiState(val vehicles: List<Vehicle> = emptyList(), val activeId: Long? = null, val loaded: Boolean = false)

@HiltViewModel
class VehicleListViewModel @Inject constructor(private val repo: VehicleRepository) : ViewModel() {
    val state: StateFlow<VehicleListUiState> = combine(repo.vehicles, repo.activeVehicle) { list, active ->
        VehicleListUiState(list, active?.id, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleListUiState())

    fun setActive(id: Long) { viewModelScope.launch { repo.setActive(id) } }

    /** Deletes [id]. Trips keep their data. If it was the active car, the first remaining one becomes active. */
    fun delete(id: Long) {
        viewModelScope.launch {
            val wasActive = repo.activeVehicle.first()?.id == id
            repo.delete(id)
            if (wasActive) repo.vehicles.first().firstOrNull()?.let { repo.setActive(it.id) }
        }
    }
}

@Composable
fun VehicleListRoute(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: VehicleListViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    VehicleListScreen(state, onBack, onEdit = onEdit, onAdd = { onEdit(0) }, onSelect = vm::setActive, onDelete = vm::delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleListScreen(
    state: VehicleListUiState,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vehicles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("setup_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.vehicles_add)) },
                modifier = Modifier.testTag("vehicles_add"),
            )
        },
    ) { padding ->
        if (state.loaded && state.vehicles.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("vehicles_screen")) {
                Text(stringResource(R.string.vehicles_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("vehicles_empty"))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).selectableGroup().testTag("vehicles_screen"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(state.vehicles, key = { it.id }) { v ->
                    VehicleRow(v, active = v.id == state.activeId, onSelect = { onSelect(v.id) }, onEdit = { onEdit(v.id) }, onDelete = { confirmDelete = v.id })
                }
            }
        }
    }
    val pending = confirmDelete?.let { id -> state.vehicles.firstOrNull { it.id == id } }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.vehicles_delete_title, pending.name)) },
            text = { Text(stringResource(R.string.vehicles_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onDelete(pending.id); confirmDelete = null }, modifier = Modifier.testTag("vehicles_delete_confirm")) {
                    Text(stringResource(R.string.vehicles_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.vehicles_cancel)) } },
        )
    }
}

@Composable
private fun VehicleRow(v: Vehicle, active: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val details = listOfNotNull(
        stringResource(v.fuel.labelRes()),
        v.displacementCc?.let { stringResource(R.string.vehicles_cc, it) },
        v.odometerKm?.let {
            val units = io.github.neotheon3.orangebox.core.designsystem.LocalUnits.current
            stringResource(R.string.vehicles_odometer, kotlin.math.round(units.distance(it.toDouble())).toInt(), units.distanceLabel)
        },
    ).joinToString(" · ")
    ListItem(
        leadingContent = { RadioButton(selected = active, onClick = null) },
        headlineContent = { Text(v.name) },
        supportingContent = {
            Text(if (active) stringResource(R.string.vehicles_active, details) else details)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.testTag("vehicle_edit_${v.id}")) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.vehicles_edit, v.name))
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("vehicle_delete_${v.id}")) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vehicles_delete_named, v.name))
                }
            }
        },
        modifier = Modifier.selectable(selected = active, onClick = onSelect, role = Role.RadioButton).testTag("vehicle_row_${v.id}"),
    )
}
