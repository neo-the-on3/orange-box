package io.github.neotheon3.orangebox.feature.driving

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.neotheon3.orangebox.core.dashboardui.DashboardSettings
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the dashboard with Robolectric native graphics into `build/outputs/dashboard/` (one PNG per case) for the
 * artwork review (CI keeps the real-device screenshots from the emulator smoke). Not a pixel diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DashboardRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun render(name: String, state: DashboardUiState, detail: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                DashboardScreen(state, onEndDriving = {}, initialShowDetail = detail, settings = DashboardSettings(reduceMotion = true))
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/outputs/dashboard/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi") @Test fun landscape() = render("landscape-fuel-cut", SampleStates.fuelCut())
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi") @Test fun landscapeOverspeed() = render("landscape-overspeed", SampleStates.overspeed())
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi") @Test fun landscapeDetail() = render("landscape-detail", SampleStates.fuelCut(), detail = true)
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi") @Test fun landscapeNoData() = render("landscape-no-data", DashboardUiState())
    @Config(qualifiers = "w360dp-h780dp-port-xhdpi") @Test fun portrait() = render("portrait-fuel-cut", SampleStates.fuelCut())
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi") @Test fun portraitFont2() = render("portrait-360x640-font-200", SampleStates.fuelCut(), fontScale = 2f)
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi") @Test fun landscapeSmall() = render("landscape-640x360", SampleStates.fuelCut())
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi") @Test fun landscapeFont2() = render("landscape-640x360-font-200", SampleStates.fuelCut(), fontScale = 2f)
    @Config(qualifiers = "w360dp-h780dp-port-xhdpi") @Test fun portraitDetail() = render("portrait-detail", SampleStates.fuelCut(), detail = true)
}
