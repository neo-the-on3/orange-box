package io.github.neotheon3.orangebox.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `light and dark schemes use brand orange (light darkened for contrast, #71)`() {
        var light = Color.Unspecified
        var dark = Color.Unspecified
        compose.setContent {
            OrangeBoxTheme(darkTheme = false) { light = MaterialTheme.colorScheme.primary }
            OrangeBoxTheme(darkTheme = true) { dark = MaterialTheme.colorScheme.primary; StatusPill("Backend: Off") }
        }
        compose.onNodeWithText("Backend: Off").assertIsDisplayed()
        assertEquals(OrangeBoxColors.OrangeDark, light)
        assertNotEquals(light, dark)
    }
}
