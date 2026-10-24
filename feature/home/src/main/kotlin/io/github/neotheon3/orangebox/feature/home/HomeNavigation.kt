package io.github.neotheon3.orangebox.feature.home

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination

fun NavGraphBuilder.homeScreen(
    onOpenSettings: () -> Unit,
    onOpenDashboard: () -> Unit,
    extraMenuEntries: @Composable () -> List<HomeMenuEntry> = { emptyList() },
) {
    composable<HomeDestination> {
        HomeRoute(onOpenSettings = onOpenSettings, onOpenDashboard = onOpenDashboard, extraMenuEntries = extraMenuEntries())
    }
}
