package io.github.neotheon3.orangebox.simulator

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dagger.hilt.android.EntryPointAccessors
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.DebugExportsEntryPoint
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.feature.home.HomeMenuEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object SimulatorDestination

/** Debug-only: adds the Simulator route and the Home overflow entries (Simulator, exports). */
object SimulatorDebugEntries : DebugEntries {
    override fun NavGraphBuilder.registerDestinations(navController: NavHostController) {
        composable<SimulatorDestination> { SimulatorRoute(onBack = { navController.popBackStack() }) }
    }

    @Composable
    override fun homeMenuEntries(navController: NavHostController): List<HomeMenuEntry> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val exports = EntryPointAccessors.fromApplication(context.applicationContext, DebugExportsEntryPoint::class.java).debugExports()
        fun run(what: String, block: suspend () -> List<java.io.File>) {
            scope.launch {
                try {
                    val files = block()
                    Toast.makeText(context, "Saved to ${files.first().parent}", Toast.LENGTH_LONG).show()
                    context.startActivity(exports.shareIntent(files, "Orange Box $what"))
                } catch (e: Exception) {
                    OrangeLog.w(OrangeLog.Areas.APP, "debug_export_failed", "what" to what, error = e)
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        return listOf(
            HomeMenuEntry("Simulator", "home_menu_simulator") {
                navController.navigate(SimulatorDestination) { launchSingleTop = true }
            },
            HomeMenuEntry("Export trips", "home_menu_export_trips") { run("trips") { exports.exportTrips() } },
            HomeMenuEntry("Export connection log", "home_menu_export_connection_log") {
                run("connection log") { listOf(exports.exportConnectionLog()) }
            },
        )
    }
}
