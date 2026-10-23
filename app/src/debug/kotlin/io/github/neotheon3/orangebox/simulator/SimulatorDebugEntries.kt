package io.github.neotheon3.orangebox.simulator

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.feature.home.HomeMenuEntry
import kotlinx.serialization.Serializable

@Serializable
data object SimulatorDestination

/** Debug-only: adds the Simulator route and its Home overflow entry. */
object SimulatorDebugEntries : DebugEntries {
    override fun NavGraphBuilder.registerDestinations(navController: NavHostController) {
        composable<SimulatorDestination> { SimulatorRoute(onBack = { navController.popBackStack() }) }
    }

    @Composable
    override fun homeMenuEntries(navController: NavHostController): List<HomeMenuEntry> = listOf(
        HomeMenuEntry("Simulator", "home_menu_simulator") {
            navController.navigate(SimulatorDestination) { launchSingleTop = true }
        },
    )
}
