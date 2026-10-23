package io.github.neotheon3.orangebox.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.feature.home.HomeDestination
import io.github.neotheon3.orangebox.feature.home.homeScreen
import io.github.neotheon3.orangebox.feature.settings.SettingsDestination
import io.github.neotheon3.orangebox.feature.settings.settingsScreen

@Composable
fun OrangeBoxNavHost(debugEntries: DebugEntries) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        homeScreen(
            onOpenSettings = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
            extraMenuEntries = { debugEntries.homeMenuEntries(navController) },
        )
        settingsScreen(onBack = { navController.popBackStack() })
        with(debugEntries) { registerDestinations(navController) }
    }
}
