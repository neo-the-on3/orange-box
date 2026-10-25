package io.github.neotheon3.orangebox.feature.trips

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Trip detail on a 1920×1080 phone in landscape (#188), with 3-button navigation on the right edge (48 dp).
 * The map is capped so the page still scrolls, and nothing runs under the navigation bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w731dp-h411dp-land-420dpi")
class TripDetailLandscapeTest {
    @get:Rule val compose = createComposeRule()

    private val navBar = WindowInsets(right = 48.dp)

    private fun show() {
        compose.setContent {
            OrangeBoxTheme { TripDetailScreen(TripDetailUiState(loading = false, detail = DetailFixture.detail(), tilesEnabled = false), locale = Locale.UK, contentInsets = navBar) }
        }
        compose.waitForIdle()
    }

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the map takes at most about half the height, so metrics show on the first screen`() {
        show()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val map = compose.onNodeWithTag("trip_detail_map_card").getUnclippedBoundsInRoot()
        val h = map.bottom - map.top
        assertTrue("map $h of ${root.bottom - root.top}", h <= (root.bottom - root.top) * 0.55f)
        compose.onNodeWithText("9.4 km").assertIsDisplayed()
        render("trip-detail-landscape")
    }

    @Test fun `content stays clear of the right-hand navigation bar`() {
        show()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val map = compose.onNodeWithTag("trip_detail_map_card").getUnclippedBoundsInRoot()
        assertTrue("map ends at ${map.right}, nav bar starts at ${root.right - 48.dp}", map.right <= root.right - 48.dp)
        compose.onNodeWithTag("trip_detail_list").performScrollToNode(hasTestTag("chart_economy"))
        val econ = compose.onNodeWithTag("chart_economy").getUnclippedBoundsInRoot()
        assertTrue("economy chart ends at ${econ.right}", econ.right <= root.right - 48.dp)
        compose.onNodeWithTag("chart_economy_end", useUnmergedTree = true).let {
            assertTrue(it.getUnclippedBoundsInRoot().right <= root.right - 48.dp)
        }
        render("trip-detail-landscape-charts")
    }

    @Test fun `a swipe below the map scrolls the page`() {
        show()
        val before = compose.onNodeWithTag("trip_detail_map_card").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("trip_detail_list").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
        compose.waitForIdle()
        // Scrolled: the map moved up, or out of the list altogether.
        val map = compose.onAllNodesWithTag("trip_detail_map_card").fetchSemanticsNodes()
        val after = map.singleOrNull()?.boundsInRoot?.top
        assertTrue("page scrolled: map top $before → $after", after == null || after < before.value)
        render("trip-detail-landscape-scrolled")
    }
}
