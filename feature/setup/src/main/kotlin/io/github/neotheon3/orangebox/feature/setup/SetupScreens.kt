package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data object OnboardingDestination
@Serializable data object VehicleSetupDestination
@Serializable data object DongleSetupDestination
@Serializable data object PermissionsDestination

fun NavGraphBuilder.setupScreens(onOnboardingFinished: () -> Unit, onBack: () -> Unit, onEditVehicle: (Long) -> Unit = {}) {
    composable<OnboardingDestination> { OnboardingRoute(onFinished = onOnboardingFinished) }
    composable<VehicleSetupDestination> { VehicleSetupRoute(onBack) }
    composable<VehicleListDestination> { VehicleListRoute(onBack, onEditVehicle) }
    composable<VehicleEditDestination> { VehicleSetupRoute(onBack) }
    composable<DongleSetupDestination> { DongleSetupRoute(onBack) }
    composable<PermissionsDestination> { PermissionsRoute(onBack) }
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(private val prefs: AppPreferencesRepository) : ViewModel() {
    val asked = prefs.preferences.map { it.askedPermissions }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    fun markAsked(p: Collection<String>) { viewModelScope.launch { prefs.markPermissionsAsked(p) } }
}

@Composable
fun VehicleSetupRoute(onBack: () -> Unit, vm: VehicleSetupViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val title = when (vm.editId) {
        null -> R.string.vehicle_title
        0L -> R.string.vehicle_add_title
        else -> R.string.vehicle_edit_title
    }
    // Opened from the Vehicles list: back to it once saved.
    androidx.compose.runtime.LaunchedEffect(s.saved) { if (s.saved && vm.editId != null) onBack() }
    SetupScaffold(stringResource(title), onBack, "vehicle_screen") {
        VehicleSetupContent(s, vm::onFormChange)
        Button(onClick = vm::save, modifier = Modifier.testTag("vehicle_save")) { Text(stringResource(R.string.vehicle_save)) }
    }
}

@Composable
fun DongleSetupRoute(onBack: () -> Unit, vm: DongleSetupViewModel = hiltViewModel(), perms: PermissionsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val asked by perms.asked.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    SetupScaffold(stringResource(R.string.dongle_title), onBack, "dongle_screen") {
        DongleSetupContent(
            state = s, onSelect = vm::select, onTest = vm::testConnection, onAutoStartChange = vm::setAutoStart,
            onOpenBluetoothSettings = rememberOpenBluetoothSettings(),
            permissionSlot = { PermissionsSection(asked, perms::markAsked, permissions = AppPermission.forDevice().filter { it == AppPermission.BLUETOOTH }) },
        )
    }
}

@Composable
fun PermissionsRoute(onBack: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val asked by vm.asked.collectAsStateWithLifecycle()
    SetupScaffold(stringResource(R.string.onboarding_permissions_title), onBack, "permissions_screen") {
        PermissionsSection(asked, vm::markAsked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScaffold(title: String, onBack: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("setup_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}
