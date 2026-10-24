package io.github.neotheon3.orangebox.feature.trips

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object TripsDestination

fun NavGraphBuilder.tripsScreen(
    onBack: () -> Unit,
    onOpenTrip: (Long) -> Unit = {},
) {
    composable<TripsDestination> { TripsRoute(onBack = onBack, onOpenTrip = onOpenTrip) }
}
