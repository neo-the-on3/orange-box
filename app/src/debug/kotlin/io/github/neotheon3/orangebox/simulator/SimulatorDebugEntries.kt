// Debug-only developer tool (never in release builds): English only, not localised (#73).
@file:Suppress("HardcodedComposeText")

package io.github.neotheon3.orangebox.simulator

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dagger.hilt.android.EntryPointAccessors
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.DebugExports
import io.github.neotheon3.orangebox.DebugExportsEntryPoint
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.feature.home.HomeMenuEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        // Trip export progress (#166): a dialog with a progress bar and Cancel while it runs; the menu entry
        // does nothing while an export is in progress, so a second tap can't start another.
        var progress by remember { mutableStateOf<DebugExports.Progress?>(null) }
        var running by remember { mutableStateOf<Job?>(null) }
        fun run(what: String, block: suspend () -> List<java.io.File>) {
            if (running?.isActive == true) return
            running = scope.launch {
                try {
                    val files = block()
                    Toast.makeText(context, "Saved to ${files.first().parent}", Toast.LENGTH_LONG).show()
                    context.startActivity(exports.shareIntent(files, "Orange Box $what"))
                } catch (e: CancellationException) {
                    Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
                    throw e
                } catch (e: Exception) {
                    OrangeLog.w(OrangeLog.Areas.APP, "debug_export_failed", "what" to what, error = e)
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    progress = null
                }
            }
        }
        progress?.let { p ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exporting trips") },
                text = {
                    Column {
                        Text("${p.done} of ${p.total} trips", modifier = Modifier.testTag("export_progress_text"))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 1f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().testTag("export_progress"),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { running?.cancel() }) { Text("Cancel") } },
            )
        }
        return listOf(
            HomeMenuEntry("Simulator", "home_menu_simulator") {
                navController.navigate(SimulatorDestination) { launchSingleTop = true }
            },
            HomeMenuEntry("Export trips", "home_menu_export_trips") {
                run("trips") {
                    progress = DebugExports.Progress(0, 0)
                    exports.exportTrips { p -> progress = p }
                }
            },
            HomeMenuEntry("Export connection log", "home_menu_export_connection_log") {
                run("connection log") { listOf(exports.exportConnectionLog()) }
            },
        )
    }
}
