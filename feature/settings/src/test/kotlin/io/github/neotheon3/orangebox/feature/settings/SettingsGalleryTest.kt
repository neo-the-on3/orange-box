package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode

/** M10 gallery (#71/#72): Settings in light, dark and 200 % font, with accessibility checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class SettingsGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settings() = compose.screenGallery("settings") {
        SettingsScreen(SettingsUiState(config = BackendConfig(BackendMode.OFF)), onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {})
    }

    @Test fun unitsAndAlerts() = compose.screenGallery("settings-units-alerts") {
        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                UnitsSettingsSection(io.github.neotheon3.orangebox.core.model.UnitPreferences.forCountry("US")) {}
                AlertsSettingsSection(io.github.neotheon3.orangebox.core.model.AlertPreferences()) {}
            }
        }
    }
}
