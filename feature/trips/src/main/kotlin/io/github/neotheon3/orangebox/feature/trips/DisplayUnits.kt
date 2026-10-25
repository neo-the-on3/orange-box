package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import io.github.neotheon3.orangebox.core.designsystem.LocalUnits
import io.github.neotheon3.orangebox.core.model.UnitPreferences

/** The units from Settings (#58). Trips are stored in metric; only what is shown is converted. */
internal val displayUnits: UnitPreferences
    @Composable @ReadOnlyComposable get() = LocalUnits.current
