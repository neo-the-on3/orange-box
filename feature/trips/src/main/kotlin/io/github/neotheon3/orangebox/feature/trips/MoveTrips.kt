package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.database.TripReassignment
import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** "Move to another vehicle" for trips of a vehicle deleted on another device (#203). [selected] is a vehicle id. */
data class MoveTripsDialog(val tripIds: Set<Long>, val targets: List<Vehicle>, val selected: Long?)

/**
 * The move flow shared by the Trips list (all affected trips) and a trip's detail (that one): pick a vehicle, move,
 * then ask for a sync so they upload now rather than at the next periodic run.
 */
class TripMover(private val reassignment: TripReassignment, private val account: BackendAccount?, private val scope: CoroutineScope) {
    val orphaned: Flow<Set<Long>> = reassignment.orphanedTripIds

    private val _dialog = MutableStateFlow<MoveTripsDialog?>(null)
    val dialog: StateFlow<MoveTripsDialog?> = _dialog.asStateFlow()

    fun start(tripIds: Set<Long>) {
        if (tripIds.isEmpty()) return
        scope.launch {
            val targets = reassignment.targets(tripIds)
            _dialog.value = MoveTripsDialog(tripIds, targets, targets.singleOrNull()?.id)
        }
    }

    fun select(vehicleId: Long) {
        _dialog.value = _dialog.value?.copy(selected = vehicleId)
    }

    fun dismiss() {
        _dialog.value = null
    }

    fun confirm() {
        val d = _dialog.value ?: return
        val target = d.selected ?: return
        scope.launch {
            val moved = reassignment.move(d.tripIds, target)
            _dialog.value = null
            if (moved > 0) account?.syncNow()
        }
    }

    companion object {
        /** No backend features (tests, previews): nothing is ever orphaned. */
        val NONE: TripReassignment = object : TripReassignment {
            override val orphanedTripIds: Flow<Set<Long>> = flowOf(emptySet())
            override suspend fun targets(tripIds: Set<Long>): List<Vehicle> = emptyList()
            override suspend fun move(tripIds: Set<Long>, vehicleId: Long): Int = 0
        }
    }
}

data class MoveTripsActions(
    val onStart: () -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** Shown while trips of a vehicle deleted elsewhere are on the phone. */
@Composable
internal fun OrphanedTripsCard(count: Int, single: Boolean, onMove: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().testTag("trips_orphaned"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.trips_vehicle_deleted_elsewhere), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                if (single) stringResource(R.string.trips_orphaned_one_body) else pluralStringResource(R.plurals.trips_orphaned_body, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onMove, modifier = Modifier.align(Alignment.End).testTag("trips_move")) {
                Text(if (single) stringResource(R.string.trips_move_one) else stringResource(R.string.trips_move_all))
            }
        }
    }
}

@Composable
internal fun MoveTripsDialogView(dialog: MoveTripsDialog, actions: MoveTripsActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(pluralStringResource(R.plurals.trips_move_title, dialog.tripIds.size, dialog.tripIds.size)) },
        text = {
            if (dialog.targets.isEmpty()) {
                Text(stringResource(R.string.trips_move_no_vehicle), modifier = Modifier.testTag("trips_move_none"))
            } else {
                Column(Modifier.selectableGroup().testTag("trips_move_targets")) {
                    Text(stringResource(R.string.trips_move_body), modifier = Modifier.padding(bottom = 8.dp))
                    dialog.targets.forEach { v ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(dialog.selected == v.id, role = Role.RadioButton) { actions.onSelect(v.id) }
                                .padding(vertical = 8.dp)
                                .testTag("trips_move_target_${v.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = dialog.selected == v.id, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(v.name)
                                listOfNotNull(v.maker, v.model).joinToString(" ").takeIf { it.isNotBlank() && it != v.name }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (dialog.targets.isNotEmpty()) {
                TextButton(onClick = actions.onConfirm, enabled = dialog.selected != null, modifier = Modifier.testTag("trips_move_confirm")) {
                    Text(stringResource(R.string.trips_move_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, modifier = Modifier.testTag("trips_move_cancel")) {
                Text(stringResource(if (dialog.targets.isEmpty()) R.string.trips_move_ok else R.string.trips_move_cancel))
            }
        },
    )
}
