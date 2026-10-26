package io.github.neotheon3.orangebox.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.R
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPrompt
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPromptController
import io.github.neotheon3.orangebox.core.driving.DriveSession
import io.github.neotheon3.orangebox.feature.diagnostics.DiagnosingPromptCard
import io.github.neotheon3.orangebox.feature.diagnostics.DiagnosticsDestination
import io.github.neotheon3.orangebox.feature.diagnostics.diagnosticsScreen
import io.github.neotheon3.orangebox.feature.driving.DashboardDestination
import io.github.neotheon3.orangebox.feature.driving.dashboardScreen
import io.github.neotheon3.orangebox.feature.home.HomeDestination
import io.github.neotheon3.orangebox.feature.home.homeScreen
import io.github.neotheon3.orangebox.feature.rankings.RankingsDestination
import io.github.neotheon3.orangebox.feature.rankings.rankingsScreen
import io.github.neotheon3.orangebox.feature.settings.SettingsDestination
import io.github.neotheon3.orangebox.feature.settings.settingsScreen
import io.github.neotheon3.orangebox.feature.setup.DongleSetupDestination
import io.github.neotheon3.orangebox.feature.setup.OnboardingDestination
import io.github.neotheon3.orangebox.feature.setup.PermissionsDestination
import io.github.neotheon3.orangebox.feature.setup.setupScreens
import io.github.neotheon3.orangebox.feature.trips.StatsDestination
import io.github.neotheon3.orangebox.feature.trips.TripDetailDestination
import io.github.neotheon3.orangebox.feature.trips.TripsDestination
import io.github.neotheon3.orangebox.feature.trips.tripsScreen
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun OrangeBoxNavHost(
    debugEntries: DebugEntries,
    showOnboarding: Boolean,
    driveSession: DriveSession,
    /** Count of "open the dashboard" requests (notification taps); each new value opens it once. */
    openDashboardRequests: Int = 0,
    /** A request arrived during onboarding and was not followed (undo any lock-screen flags). */
    onDashboardRequestIgnored: () -> Unit = {},
    /** "Car Diagnosing…" at drive start (#50); null in tests that don't need it. */
    diagnosingPrompt: DiagnosingPromptController? = null,
    /** Crash / emergency prompt (M7), drawn above everything; null in tests that don't need it. */
    crashPrompt: io.github.neotheon3.orangebox.feature.driving.CrashPromptController? = null,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    // Chosen once: finishing onboarding navigates to Home and drops Onboarding from the back stack.
    val start: Any = remember { if (showOnboarding) OnboardingDestination else HomeDestination }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = start) {
            setupScreens(
                onOnboardingFinished = {
                    navController.navigate(HomeDestination) {
                        popUpTo<OnboardingDestination> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                onEditVehicle = { id ->
                    navController.navigate(io.github.neotheon3.orangebox.feature.setup.VehicleEditDestination(id)) { launchSingleTop = true }
                },
            )
            homeScreen(
                onOpenSettings = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
                onOpenDashboard = { navController.openDashboard() },
                extraMenuEntries = { debugEntries.homeMenuEntries(navController) },
                onOpenTrips = { navController.navigate(TripsDestination) { launchSingleTop = true } },
                onOpenDiagnostics = { navController.openDiagnostics() },
            )
            tripsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrip = { id -> navController.navigate(TripDetailDestination(id)) { launchSingleTop = true } },
                onOpenStats = { navController.navigate(StatsDestination) { launchSingleTop = true } },
                onOpenRankings = { navController.navigate(RankingsDestination) { launchSingleTop = true } },
            )
            rankingsScreen(onBack = { navController.popBackStack() })
            dashboardScreen(
                onEndDriving = { navController.returnHome() },
                onBack = { navController.returnHome() },
                onOpenDiagnostics = { navController.openDiagnostics() },
            )
            diagnosticsScreen(onBack = { navController.popBackStack() })
            settingsScreen(
                onBack = { navController.popBackStack() },
                onOpenVehicle = { navController.navigate(io.github.neotheon3.orangebox.feature.setup.VehicleListDestination) { launchSingleTop = true } },
                onOpenDongle = { navController.navigate(DongleSetupDestination) { launchSingleTop = true } },
                onOpenPermissions = { navController.navigate(PermissionsDestination) { launchSingleTop = true } },
            )
            with(debugEntries) { registerDestinations(navController) }
        }
        LaunchedEffect(openDashboardRequests) {
            // Not during onboarding: the notice and permissions come first.
            if (openDashboardRequests > 0) {
                if (showOnboarding) onDashboardRequestIgnored() else navController.openDashboard()
            }
        }
        if (diagnosingPrompt != null && !showOnboarding) {
            DrivePromptOverlay(diagnosingPrompt, navController)
        }
        EndDriveSnackbar(driveSession, snackbar, onUndo = { navController.openDashboard() })
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).testTag("app_snackbar"),
        )
        // Also during onboarding: a manual prompt can't be opened there, but an impact always shows.
        crashPrompt?.let { io.github.neotheon3.orangebox.feature.driving.CrashPromptOverlay(it) }
    }
}

/** "Drive ended" with Undo while [DriveSession.pendingEnd] (5 s); Undo resumes and reopens the dashboard. */
@Composable
private fun EndDriveSnackbar(session: DriveSession, host: SnackbarHostState, onUndo: () -> Unit) {
    val pending by session.pendingEnd.collectAsStateWithLifecycle()
    val message = stringResource(R.string.drive_ended)
    val undo = stringResource(R.string.drive_ended_undo)
    LaunchedEffect(pending) {
        if (!pending) {
            host.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val result = withTimeoutOrNull(DriveSession.UNDO_WINDOW_MILLIS) {
            host.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) {
            session.undoEnd()
            onUndo()
        }
    }
}

/** The drive-start prompt floats over whatever is on screen, except Diagnostics itself. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DrivePromptOverlay(controller: DiagnosingPromptController, navController: NavHostController) {
    val prompt by controller.prompt.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val onDiagnostics = entry?.destination?.hasRoute<DiagnosticsDestination>() == true
    if (prompt == DiagnosingPrompt.Hidden || onDiagnostics) return
    DiagnosingPromptCard(
        prompt,
        onDismiss = controller::dismiss,
        onOpenDiagnostics = {
            controller.dismiss()
            navController.openDiagnostics()
        },
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp, start = 12.dp, end = 12.dp),
    )
}

private fun NavHostController.openDiagnostics() = navigate(DiagnosticsDestination) { launchSingleTop = true }

private fun NavHostController.openDashboard() = navigate(DashboardDestination) { launchSingleTop = true }

private fun NavHostController.returnHome() {
    if (!popBackStack<HomeDestination>(inclusive = false)) {
        navigate(HomeDestination) { popUpTo(graph.id) { inclusive = true } }
    }
}
