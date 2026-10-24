package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** #143: the bezel arch must never be drawn under the status bar. */
@RunWith(RobolectricTestRunner::class)
class BezelArchTest {
    @get:Rule val compose = createComposeRule()

    @Config(sdk = [25])
    @Test fun `API 25 without dispatched insets falls back to the status_bar_height dimen`() {
        var top: Dp = (-1).dp
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            top = statusBarArchTop()
        }
        compose.waitForIdle()
        val dimenPx = statusBarHeightPx(ApplicationProvider.getApplicationContext())
        assertTrue("platform dimen present", dimenPx > 0)
        assertEquals(dimenPx / density, top.value, 0.5f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [34], qualifiers = "w400dp-h700dp-xhdpi")
    @Test fun `arch is clipped at the status bar and shows below it`() {
        val statusBar = 24.dp
        compose.setContent { BezelBackground(Modifier.size(400.dp, 700.dp), archTop = statusBar) {} }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val topPx = (statusBar.value * 2f).roundToInt() // xhdpi
        val bg = DashboardColors.Background.toArgb()
        for (y in 0 until topPx - 1) for (x in 0 until bmp.width step 4) {
            assertEquals("pixel ($x, $y) under the status bar must be plain background", bg, bmp.getPixel(x, y))
        }
        val cx = bmp.width / 2
        assertTrue("arch visible just below the status bar", bmp.getPixel(cx, topPx + 10) != bg)
    }
}
