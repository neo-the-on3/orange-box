package io.github.neotheon3.orangebox.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object SettingsDestination

fun NavGraphBuilder.settingsScreen(
    onBack: () -> Unit,
    onOpenVehicle: () -> Unit = {},
    onOpenDongle: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
) {
    composable<SettingsDestination> {
        SettingsRoute(onBack = onBack, onOpenVehicle = onOpenVehicle, onOpenDongle = onOpenDongle, onOpenPermissions = onOpenPermissions)
    }
}
