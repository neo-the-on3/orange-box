package io.github.neotheon3.orangebox.feature.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.dashboardui.DashboardTheme
import io.github.neotheon3.orangebox.core.dashboardui.PanelGlow
import io.github.neotheon3.orangebox.core.dashboardui.RpmCluster
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Robolectric screenshots of the RDF1 Home changes (#192) and the red RPM panel (#198), in build/outputs/home/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class HomeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))

    /** Hard acceleration on a petrol car: load 92 %, 4200 rpm, +3.3 m/s². */
    private val hardDriving: TelemetrySnapshot = TelemetryEngine().run {
        val pids = { speed: Double -> mapOf(ObdPid.SPEED to speed, ObdPid.RPM to 4200.0, ObdPid.ENGINE_LOAD to 92.0, ObdPid.THROTTLE to 80.0, ObdPid.MAF to 40.0, ObdPid.COOLANT_TEMP to 90.0) }
        onSample(VehicleSample(0, pids(60.0)))
        onSample(VehicleSample(500, pids(66.0)))
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { OrangeBoxTheme(darkTheme = dark) { content() } }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/home/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeIdle() = shot("home-drive-idle") { HomeScreen(HomeUiState(loading = false), onOpenSettings = {}) }

    @Test fun homeIdleDark() = shot("home-drive-idle-dark", dark = true) { HomeScreen(HomeUiState(loading = false), onOpenSettings = {}) }

    @Test fun homeConnectedHardDriving() {
        val dashboard = DashboardStateMapper.map(connected, hardDriving)
        assertEquals(PanelGlow.ECO_RED, dashboard.leftGlow)
        shot("home-drive-connected-red-rpm") {
            HomeScreen(HomeUiState(loading = false, obd = connected, live = hardDriving, dashboard = dashboard), onOpenSettings = {})
        }
    }

    @Test fun rpmPanelRed() {
        val dashboard = DashboardStateMapper.map(connected, hardDriving)
        assertEquals(PanelGlow.ECO_RED, dashboard.leftGlow)
        shot("rpm-panel-red") {
            DashboardTheme {
                Box(Modifier.padding(0.dp)) {
                    androidx.compose.material3.Surface(color = DashboardColors.Background) {
                        Box(Modifier.padding(12.dp)) { RpmCluster(dashboard, Modifier.size(360.dp, 300.dp)) }
                    }
                }
            }
        }
    }
}
