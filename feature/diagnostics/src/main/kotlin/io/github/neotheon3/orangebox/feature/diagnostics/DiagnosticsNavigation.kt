package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object DiagnosticsDestination

fun NavGraphBuilder.diagnosticsScreen(onBack: () -> Unit) {
    composable<DiagnosticsDestination> { DiagnosticsRoute(onBack = onBack) }
}
