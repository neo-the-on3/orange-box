package io.github.neotheon3.orangebox.feature.driving

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object DashboardDestination

/**
 * The driving dashboard. [onEndDriving] runs after the drive end was requested (undo window started);
 * it should return to Home. [onBack] just leaves the screen; the drive continues.
 */
fun NavGraphBuilder.dashboardScreen(onEndDriving: () -> Unit, onBack: () -> Unit, onOpenDiagnostics: () -> Unit = {}) {
    composable<DashboardDestination> {
        DashboardRoute(onEndDriving = onEndDriving, onBack = onBack, onOpenDiagnostics = onOpenDiagnostics)
    }
}
