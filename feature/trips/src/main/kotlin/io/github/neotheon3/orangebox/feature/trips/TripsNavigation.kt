package io.github.neotheon3.orangebox.feature.trips

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object TripsDestination

@Serializable
data object StatsDestination

@Serializable
data class TripDetailDestination(val tripId: Long) {
    companion object {
        /** Name of the property above: the SavedStateHandle key the type-safe route stores it under. */
        const val ARG_TRIP_ID = "tripId"
    }
}

fun NavGraphBuilder.tripsScreen(
    onBack: () -> Unit,
    onOpenTrip: (Long) -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    composable<TripsDestination> { TripsRoute(onBack = onBack, onOpenTrip = onOpenTrip, onOpenStats = onOpenStats) }
    composable<TripDetailDestination> { TripDetailRoute(onBack = onBack) }
    composable<StatsDestination> { io.github.neotheon3.orangebox.feature.trips.stats.StatsRoute(onBack = onBack) }
}
