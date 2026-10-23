package io.github.neotheon3.orangebox.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.feature.home.HomeDestination
import io.github.neotheon3.orangebox.feature.home.homeScreen
import io.github.neotheon3.orangebox.feature.settings.SettingsDestination
import io.github.neotheon3.orangebox.feature.settings.settingsScreen
import io.github.neotheon3.orangebox.feature.setup.DongleSetupDestination
import io.github.neotheon3.orangebox.feature.setup.OnboardingDestination
import io.github.neotheon3.orangebox.feature.setup.PermissionsDestination
import io.github.neotheon3.orangebox.feature.setup.VehicleSetupDestination
import io.github.neotheon3.orangebox.feature.setup.setupScreens

@Composable
fun OrangeBoxNavHost(debugEntries: DebugEntries, showOnboarding: Boolean) {
    val navController = rememberNavController()
    // Chosen once: finishing onboarding navigates to Home and drops Onboarding from the back stack.
    val start: Any = remember { if (showOnboarding) OnboardingDestination else HomeDestination }
    NavHost(navController = navController, startDestination = start) {
        setupScreens(
            onOnboardingFinished = {
                navController.navigate(HomeDestination) {
                    popUpTo<OnboardingDestination> { inclusive = true }
                    launchSingleTop = true
                }
            },
            onBack = { navController.popBackStack() },
        )
        homeScreen(
            onOpenSettings = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
            extraMenuEntries = { debugEntries.homeMenuEntries(navController) },
        )
        settingsScreen(
            onBack = { navController.popBackStack() },
            onOpenVehicle = { navController.navigate(VehicleSetupDestination) { launchSingleTop = true } },
            onOpenDongle = { navController.navigate(DongleSetupDestination) { launchSingleTop = true } },
            onOpenPermissions = { navController.navigate(PermissionsDestination) { launchSingleTop = true } },
        )
        with(debugEntries) { registerDestinations(navController) }
    }
}
