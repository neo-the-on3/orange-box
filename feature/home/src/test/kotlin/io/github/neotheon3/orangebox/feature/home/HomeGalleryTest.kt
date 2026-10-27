package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M10 gallery (#71/#72): Home in light, dark and 200 % font, with accessibility checks; build/outputs/gallery/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class HomeGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))
    private val live = TelemetryEngine().run {
        val pids = { speed: Double -> mapOf(ObdPid.SPEED to speed, ObdPid.RPM to 2100.0, ObdPid.ENGINE_LOAD to 40.0, ObdPid.THROTTLE to 20.0, ObdPid.MAF to 12.0, ObdPid.COOLANT_TEMP to 88.0) }
        onSample(VehicleSample(0, pids(48.0)))
        onSample(VehicleSample(500, pids(49.0)))
    }

    @Test fun idle() = compose.screenGallery("home-idle") { HomeScreen(HomeUiState(loading = false), onOpenSettings = {}) }

    @Test fun connected() = compose.screenGallery("home-connected") {
        HomeScreen(HomeUiState(loading = false, obd = connected, live = live, dashboard = DashboardStateMapper.map(connected, live)), onOpenSettings = {})
    }
}
