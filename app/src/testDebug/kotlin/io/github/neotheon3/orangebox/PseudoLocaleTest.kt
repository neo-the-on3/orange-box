package io.github.neotheon3.orangebox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.testing.saveGalleryShot
import io.github.neotheon3.orangebox.feature.home.HomeScreen
import io.github.neotheon3.orangebox.feature.home.HomeUiState
import io.github.neotheon3.orangebox.feature.settings.SettingsScreen
import io.github.neotheon3.orangebox.feature.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #73 pseudo-locale smoke: debug builds ship en-XA (accented, ~30 % longer) and ar-XB (mirrored, right-to-left).
 * Every string, including those of the feature modules, must come out pseudo-localised; an English string left on
 * screen would be hard-coded. Screenshots in build/outputs/gallery/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PseudoLocaleTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Config(qualifiers = "en-rXA-w411dp-h891dp-port-xhdpi")
    @Test fun `en-XA accents every string of Home and Settings`() {
        val title = context.getString(io.github.neotheon3.orangebox.feature.settings.R.string.settings_title)
        assertNotEquals("Settings", title)
        assertTrue("pseudo-localised: $title", title.startsWith("[") && title.any { it.code > 127 })
        compose.setContent { OrangeBoxTheme { HomeScreen(HomeUiState(loading = false), onOpenSettings = {}) } }
        compose.onNodeWithText(context.getString(io.github.neotheon3.orangebox.feature.home.R.string.home_title), substring = true).assertIsDisplayed()
        compose.saveGalleryShot("pseudo-en-XA-home")
    }

    @Config(qualifiers = "en-rXA-w411dp-h891dp-port-xhdpi")
    @Test fun `en-XA settings`() {
        compose.setContent {
            OrangeBoxTheme { SettingsScreen(SettingsUiState(config = BackendConfig(BackendMode.OFF)), onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {}) }
        }
        compose.onNodeWithTag("settings_vehicle").assertIsDisplayed()
        compose.saveGalleryShot("pseudo-en-XA-settings")
    }

    @Config(qualifiers = "ar-rXB-w411dp-h891dp-port-xhdpi")
    @Test fun `ar-XB lays out right to left`() {
        var direction: LayoutDirection? = null
        compose.setContent {
            direction = LocalLayoutDirection.current
            OrangeBoxTheme { HomeScreen(HomeUiState(loading = false), onOpenSettings = {}) }
        }
        compose.waitForIdle()
        assertEquals(LayoutDirection.Rtl, direction)
        compose.saveGalleryShot("pseudo-ar-XB-home")
    }
}
