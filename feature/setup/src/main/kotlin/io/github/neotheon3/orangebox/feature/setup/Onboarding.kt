package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { WELCOME, DISCLAIMER, PERMISSIONS, VEHICLE, DONGLE, DONE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val prefs: AppPreferences = AppPreferences(),
    val disclaimerChecked: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: AppPreferencesRepository) : ViewModel() {
    private val step = MutableStateFlow(OnboardingStep.WELCOME)
    private val checked = MutableStateFlow(false)

    val state: StateFlow<OnboardingUiState> = combine(step, prefs.preferences, checked) { s, p, c ->
        OnboardingUiState(s, p, c || p.disclaimerAccepted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun onDisclaimerChecked(value: Boolean) { checked.value = value }

    fun next() {
        val current = step.value
        if (current == OnboardingStep.DISCLAIMER) {
            if (!state.value.disclaimerChecked) return
            viewModelScope.launch { prefs.setDisclaimerAccepted(true) }
        }
        if (current == OnboardingStep.DONE) return
        step.value = OnboardingStep.entries[current.ordinal + 1]
        OrangeLog.i(OrangeLog.Areas.APP, "onboarding_step", "step" to step.value.name.lowercase())
    }

    fun back(): Boolean {
        val current = step.value
        if (current == OnboardingStep.WELCOME) return false
        step.value = OnboardingStep.entries[current.ordinal - 1]
        return true
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setOnboardingComplete(true)
            OrangeLog.i(OrangeLog.Areas.APP, "onboarding_complete")
            onDone()
        }
    }

    fun markAsked(permissions: Collection<String>) { viewModelScope.launch { prefs.markPermissionsAsked(permissions) } }
}

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = state.step != OnboardingStep.WELCOME) { viewModel.back() }
    OnboardingScreen(
        state = state,
        onNext = viewModel::next,
        onDisclaimerChecked = viewModel::onDisclaimerChecked,
        onFinish = { viewModel.finish(onFinished) },
        permissions = { PermissionsSection(state.prefs.askedPermissions, viewModel::markAsked) },
        vehicle = { onSaved -> VehicleStep(onSaved) },
        dongle = { DongleStep(state.prefs.askedPermissions, viewModel::markAsked) },
    )
}

@Composable
private fun VehicleStep(onSaved: () -> Unit, vm: VehicleSetupViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) { if (s.saved) onSaved() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VehicleSetupContent(s, vm::onFormChange)
        Button(onClick = vm::save, modifier = Modifier.testTag("vehicle_save")) { Text(stringResource(R.string.vehicle_save)) }
    }
}

@Composable
private fun DongleStep(asked: Set<String>, onAsked: (Collection<String>) -> Unit, vm: DongleSetupViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    DongleSetupContent(
        state = s, onSelect = vm::select, onTest = vm::testConnection, onAutoStartChange = vm::setAutoStart,
        onOpenBluetoothSettings = rememberOpenBluetoothSettings(),
        permissionSlot = { PermissionsSection(asked, onAsked, permissions = AppPermission.forDevice().filter { it == AppPermission.BLUETOOTH }) },
    )
}

/** Stateless onboarding (Material 3). Steps are slots so tests can render each one without Hilt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onNext: () -> Unit,
    onDisclaimerChecked: (Boolean) -> Unit,
    onFinish: () -> Unit,
    permissions: @Composable () -> Unit,
    vehicle: @Composable (onSaved: () -> Unit) -> Unit,
    dongle: @Composable () -> Unit,
) {
    val step = state.step
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(step.titleRes()), modifier = Modifier.semantics { heading() }) })
                LinearProgressIndicator(
                    progress = { (step.ordinal + 1f) / OnboardingStep.entries.size },
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_progress"),
                )
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step == OnboardingStep.VEHICLE || step == OnboardingStep.DONGLE) {
                    TextButton(onClick = onNext, modifier = Modifier.testTag("onboarding_skip")) { Text(stringResource(R.string.onboarding_skip)) }
                }
                when (step) {
                    OnboardingStep.DONE -> Button(onClick = onFinish, modifier = Modifier.testTag("onboarding_finish")) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                    OnboardingStep.VEHICLE -> Unit // "Save vehicle" advances
                    else -> Button(
                        onClick = onNext,
                        enabled = step != OnboardingStep.DISCLAIMER || state.disclaimerChecked,
                        modifier = Modifier.testTag("onboarding_next"),
                    ) {
                        Text(stringResource(if (step == OnboardingStep.WELCOME) R.string.onboarding_get_started else R.string.onboarding_continue))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("onboarding_${step.name.lowercase()}"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.onboarding_welcome_points), style = MaterialTheme.typography.bodyMedium)
                }
                OnboardingStep.DISCLAIMER -> {
                    Text(stringResource(R.string.onboarding_disclaimer_body), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = state.disclaimerChecked, onValueChange = onDisclaimerChecked, role = Role.Checkbox)
                            .padding(vertical = 8.dp)
                            .testTag("onboarding_disclaimer_accept"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.disclaimerChecked, onCheckedChange = null)
                        Text(stringResource(R.string.onboarding_disclaimer_accept), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                OnboardingStep.PERMISSIONS -> {
                    Text(stringResource(R.string.onboarding_permissions_body))
                    permissions()
                }
                OnboardingStep.VEHICLE -> {
                    Text(stringResource(R.string.onboarding_vehicle_body))
                    vehicle(onNext)
                }
                OnboardingStep.DONGLE -> {
                    Text(stringResource(R.string.onboarding_dongle_body))
                    dongle()
                }
                OnboardingStep.DONE -> Text(stringResource(R.string.onboarding_done_body), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun OnboardingStep.titleRes() = when (this) {
    OnboardingStep.WELCOME -> R.string.onboarding_welcome_title
    OnboardingStep.DISCLAIMER -> R.string.onboarding_disclaimer_title
    OnboardingStep.PERMISSIONS -> R.string.onboarding_permissions_title
    OnboardingStep.VEHICLE -> R.string.onboarding_vehicle_title
    OnboardingStep.DONGLE -> R.string.onboarding_dongle_title
    OnboardingStep.DONE -> R.string.onboarding_done_title
}
