package io.github.neotheon3.orangebox.feature.trips.charts

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.feature.trips.DetailFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Screenshot tests for the trip charts (#45); PNGs land in build/outputs/trips/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-port-xhdpi")
class TripChartsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dark: Boolean = false, located: Boolean = true) {
        val d = DetailFixture.detail(located)
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) {
                Surface {
                    TripCharts(d.speedZones, d.speedSeries.map { (t, v) -> SeriesPoint(t, v) }, d.economySeries, Locale.UK, Modifier.padding(16.dp))
                }
            }
        }
    }

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `speed zones, speed and economy`() {
        show()
        compose.onNodeWithTag("chart_speed_zones").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stopped km/h: 0:01:01, 4 percent").assertExists()
        compose.onNodeWithTag("chart_speed").assertIsDisplayed()
        compose.onNodeWithTag("chart_economy").assertIsDisplayed()
        render("trip-charts")
    }

    @Test fun `dark theme`() {
        show(dark = true)
        render("trip-charts-dark")
    }

    @Test fun `no samples, no charts`() {
        compose.setContent {
            OrangeBoxTheme { TripCharts(SpeedZones(TripSeries.SPEED_ZONE_BOUNDS, List(6) { 0.0 }), emptyList(), emptyList(), Locale.UK) }
        }
        compose.onAllNodesWithTag("chart_speed_zones").assertCountEquals(0)
        compose.onAllNodesWithTag("chart_speed").assertCountEquals(0)
    }
}
