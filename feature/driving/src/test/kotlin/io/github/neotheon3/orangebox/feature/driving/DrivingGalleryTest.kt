package io.github.neotheon3.orangebox.feature.driving

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.dashboardui.DashboardSettings
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M10 gallery (#71/#72): the legacy-style dashboard keeps its own always-dark palette, so light and dark app themes must
 * render it the same; 200 % font must not clip the gauges. With accessibility checks; build/outputs/gallery/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrivingGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val settings = DashboardSettings(reduceMotion = true)

    @Config(qualifiers = "w780dp-h360dp-land-xhdpi")
    @Test fun dashboardLandscape() = compose.screenGallery("dashboard-landscape") {
        DashboardScreen(SampleStates.fuelCut(), onEndDriving = {}, settings = settings)
    }

    @Config(qualifiers = "w360dp-h780dp-port-xhdpi")
    @Test fun dashboardPortrait() = compose.screenGallery("dashboard-portrait") {
        DashboardScreen(SampleStates.overspeed(), onEndDriving = {}, settings = settings)
    }

    @Config(qualifiers = "w360dp-h780dp-port-xhdpi")
    @Test fun dashboardDetail() = compose.screenGallery("dashboard-detail") {
        DashboardScreen(SampleStates.fuelCut(), onEndDriving = {}, initialShowDetail = true, settings = settings)
    }

    @Config(qualifiers = "w360dp-h780dp-port-xhdpi")
    @Test fun dashboardHighContrast() = compose.screenGallery("dashboard-high-contrast", GalleryVariant.THEMES) {
        DashboardScreen(SampleStates.fuelCut(), onEndDriving = {}, settings = DashboardSettings(reduceMotion = true, highContrast = true))
    }
}
