package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAppPreferencesRepository(initial: AppPreferences = AppPreferences()) : AppPreferencesRepository {
    val state = MutableStateFlow(initial)
    override val preferences: StateFlow<AppPreferences> = state

    override suspend fun setDisclaimerAccepted(accepted: Boolean) { state.value = state.value.copy(disclaimerAccepted = accepted) }
    override suspend fun setOnboardingComplete(complete: Boolean) { state.value = state.value.copy(onboardingComplete = complete) }
    override suspend fun setAutoStartOnDongleConnect(enabled: Boolean) { state.value = state.value.copy(autoStartOnDongleConnect = enabled) }
    override suspend fun setActiveVehicle(id: Long?) { state.value = state.value.copy(activeVehicleId = id) }
    override suspend fun setOnboardingStep(step: String?) { state.value = state.value.copy(onboardingStep = step) }
    override suspend fun setDashboard(dashboard: io.github.neotheon3.orangebox.core.model.DashboardPreferences) {
        state.value = state.value.copy(
            dashboard = dashboard.copy(
                overspeedKph = io.github.neotheon3.orangebox.core.model.DashboardPreferences.clampOverspeed(dashboard.overspeedKph),
                emergencyNumber = dashboard.emergencyNumber?.trim()
                    ?.takeIf(io.github.neotheon3.orangebox.core.model.DashboardPreferences::isValidEmergencyNumber),
            ),
        )
    }
    override suspend fun setMapTilesEnabled(enabled: Boolean) { state.value = state.value.copy(mapTilesEnabled = enabled) }
    override suspend fun setCrash(crash: io.github.neotheon3.orangebox.core.model.CrashPreferences) {
        state.value = state.value.copy(
            crash = crash.copy(
                thresholdG = io.github.neotheon3.orangebox.core.model.CrashPreferences.clampThreshold(crash.thresholdG),
                countdownSeconds = io.github.neotheon3.orangebox.core.model.CrashPreferences.clampCountdown(crash.countdownSeconds),
                contactNumber = io.github.neotheon3.orangebox.core.model.CrashPreferences.normalizeContactNumber(crash.contactNumber),
                contactName = crash.contactName?.trim()?.takeIf {
                    it.isNotEmpty() && io.github.neotheon3.orangebox.core.model.CrashPreferences.normalizeContactNumber(crash.contactNumber) != null
                },
            ),
        )
    }
    override suspend fun setAlerts(alerts: io.github.neotheon3.orangebox.core.model.AlertPreferences) { state.value = state.value.copy(alerts = alerts) }
    override suspend fun setUnits(units: io.github.neotheon3.orangebox.core.model.UnitPreferences) { state.value = state.value.copy(units = units) }
    override suspend fun markPermissionsAsked(permissions: Collection<String>) {
        state.value = state.value.copy(askedPermissions = state.value.askedPermissions + permissions)
    }
}
