package io.github.neotheon3.orangebox.feature.rankings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Server stats, rankings and points (#68). Reached from Trips → Stats when the member features are available. */
@Serializable
data object RankingsDestination

fun NavGraphBuilder.rankingsScreen(onBack: () -> Unit) {
    composable<RankingsDestination> { RankingsRoute(onBack = onBack) }
}
