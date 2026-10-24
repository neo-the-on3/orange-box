package io.github.neotheon3.orangebox

import android.content.Intent
import io.github.neotheon3.orangebox.core.model.BackendMode

/**
 * Applies launch-time overrides carried on the start intent.
 *
 * Debug builds honour `--es backend_mode off|fake|remote` and `--es onboarding skip|reset` (see `src/debug`).
 * Release builds bind a no-op implementation (see `src/release`), so the extra is ignored.
 */
interface LaunchOverrides {
    /** Applies overrides from [intent]; returns the mode that was applied, or null if none. */
    fun apply(intent: Intent?): BackendMode?
}

object LaunchExtras {
    const val BACKEND_MODE = "backend_mode"
    const val ONBOARDING = "onboarding"
    /** Debug only: `seed` inserts a few demo trips (one with a route) once. */
    const val DEMO_TRIPS = "demo_trips"

    enum class Onboarding { SKIP, RESET }

    /** `skip` marks onboarding done (for scripted runs); `reset` shows it again. Anything else is ignored. */
    fun resolveOnboarding(extraValue: String?): Onboarding? = when (extraValue?.trim()?.lowercase()) {
        "skip" -> Onboarding.SKIP
        "reset" -> Onboarding.RESET
        else -> null
    }

    /** Pure parsing used by the debug implementation; invalid values yield null (keep the saved mode). */
    fun resolveBackendMode(extraValue: String?): BackendMode? = BackendMode.parse(extraValue)
}
