package io.github.neotheon3.orangebox.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.neotheon3.orangebox.core.model.UnitPreferences

/** Display units from Settings (M8, #58), provided once at the app root; metric in tests and previews. */
val LocalUnits = staticCompositionLocalOf { UnitPreferences() }
