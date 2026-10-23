package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.AppPreferences
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
    /** True until the saved progress has been read, so a resumed flow doesn't flash the Welcome step. */
    val restoring: Boolean = false,
)

/**
 * Progress survives process death twice over (#122): the step and the checkbox live in [SavedStateHandle]
 * (restored when Android recreates the task), and the step is also written to DataStore, so a cold start
 * after the task was swiped away resumes at the same step. Finishing clears it.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: AppPreferencesRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val savedStep = saved.getStateFlow<String?>(KEY_STEP, null)
    private val checked = saved.getStateFlow(KEY_CHECKED, false)

    val state: StateFlow<OnboardingUiState> = combine(savedStep, prefs.preferences, checked) { s, p, c ->
        val accepted = c || p.disclaimerAccepted
        OnboardingUiState(resolveStep(s ?: p.onboardingStep, accepted), p, accepted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState(restoring = true))

    fun onDisclaimerChecked(value: Boolean) { saved[KEY_CHECKED] = value }

    fun next() {
        val s = state.value
        if (s.restoring) return
        val current = s.step
        if (current == OnboardingStep.DISCLAIMER) {
            if (!s.disclaimerChecked) return
            viewModelScope.launch { prefs.setDisclaimerAccepted(true) }
        }
        if (current == OnboardingStep.DONE) return
        val target = OnboardingStep.entries[current.ordinal + 1]
        moveTo(target)
        OrangeLog.i(OrangeLog.Areas.APP, "onboarding_step", "step" to target.name.lowercase())
    }

    fun back(): Boolean {
        val current = state.value.step
        if (current == OnboardingStep.WELCOME) return false
        moveTo(OnboardingStep.entries[current.ordinal - 1])
        return true
    }

    private fun moveTo(step: OnboardingStep) {
        saved[KEY_STEP] = step.name
        viewModelScope.launch { prefs.setOnboardingStep(step.name) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setOnboardingComplete(true)
            prefs.setOnboardingStep(null)
            OrangeLog.i(OrangeLog.Areas.APP, "onboarding_complete")
            onDone()
        }
    }

    fun markAsked(permissions: Collection<String>) { viewModelScope.launch { prefs.markPermissionsAsked(permissions) } }

    internal companion object {
        const val KEY_STEP = "onboarding_step"
        const val KEY_CHECKED = "onboarding_disclaimer_checked"

        /**
         * Unknown names fall back to Welcome; nothing past the disclaimer without accepting it. The checkbox
         * counts as accepted, since the DataStore write that records it lands a moment later.
         */
        fun resolveStep(name: String?, disclaimerAccepted: Boolean): OnboardingStep {
            val step = OnboardingStep.entries.firstOrNull { it.name == name } ?: OnboardingStep.WELCOME
            return if (step > OnboardingStep.DISCLAIMER && !disclaimerAccepted) OnboardingStep.DISCLAIMER else step
        }
    }
}

@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.restoring) return
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
    /** Insets the bottom action bar keeps clear of (nav bar, cutout, IME). A parameter so tests can fake them. */
    bottomBarInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
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
                // Edge-to-edge: Scaffold doesn't inset a custom bottomBar, so keep the buttons above the
                // navigation bar (bottom in portrait, side in landscape on 3-button nav) (#119).
                Modifier.fillMaxWidth().windowInsetsPadding(bottomBarInsets).padding(16.dp).testTag("onboarding_bottom_bar"),
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
