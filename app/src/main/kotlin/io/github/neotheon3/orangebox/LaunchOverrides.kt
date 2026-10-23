package io.github.neotheon3.orangebox

import android.content.Intent
import io.github.neotheon3.orangebox.core.model.BackendMode

/**
 * Applies launch-time overrides carried on the start intent.
 *
 * Debug builds honour `--es backend_mode off|fake|remote` (see `src/debug`).
 * Release builds bind a no-op implementation (see `src/release`), so the extra is ignored.
 */
interface LaunchOverrides {
    /** Applies overrides from [intent]; returns the mode that was applied, or null if none. */
    fun apply(intent: Intent?): BackendMode?
}

object LaunchExtras {
    const val BACKEND_MODE = "backend_mode"

    /** Pure parsing used by the debug implementation; invalid values yield null (keep the saved mode). */
    fun resolveBackendMode(extraValue: String?): BackendMode? = BackendMode.parse(extraValue)
}
