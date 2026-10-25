package io.github.neotheon3.orangebox.core.model

/** App-level choices made during onboarding and in Settings. */
data class AppPreferences(
    val onboardingComplete: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    /** Connect automatically when the selected dongle's Bluetooth link comes up. Off by default. */
    val autoStartOnDongleConnect: Boolean = false,
    val activeVehicleId: Long? = null,
    /** Runtime permissions the app has asked for at least once (to tell "denied" from "never asked"). */
    val askedPermissions: Set<String> = emptySet(),
    /** Onboarding step reached but not finished (enum name), so a restart resumes there. Null when not started or done. */
    val onboardingStep: String? = null,
    val dashboard: DashboardPreferences = DashboardPreferences(),
    /** Trip-detail map backdrop from OpenStreetMap's tile server (the route is drawn either way). */
    val mapTilesEnabled: Boolean = true,
    /** Impact detection and the crash prompt (M7). */
    val crash: CrashPreferences = CrashPreferences(),
)
