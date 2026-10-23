package io.github.neotheon3.orangebox

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import io.github.neotheon3.orangebox.feature.home.HomeMenuEntry

/**
 * Hook for debug-only screens (e.g. the Simulator). Release builds bind [NoDebugEntries],
 * so debug destinations are not part of the release navigation graph.
 */
interface DebugEntries {
    fun NavGraphBuilder.registerDestinations(navController: NavHostController)

    @Composable
    fun homeMenuEntries(navController: NavHostController): List<HomeMenuEntry>
}

object NoDebugEntries : DebugEntries {
    override fun NavGraphBuilder.registerDestinations(navController: NavHostController) = Unit

    @Composable
    override fun homeMenuEntries(navController: NavHostController): List<HomeMenuEntry> = emptyList()
}
