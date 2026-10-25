package io.github.neotheon3.orangebox.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.UnitPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Robolectric screenshots of the M7/M8 Settings sections, written to build/outputs/settings/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) { Surface { Box(Modifier.padding(16.dp)) { content() } } }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/settings/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun units() = shot("settings_units") { UnitsSettingsSection(UnitPreferences.forCountry("US")) {} }

    @Test fun unitsDark() = shot("settings_units_dark", dark = true) { UnitsSettingsSection(UnitPreferences()) {} }

    @Test fun alerts() = shot("settings_alerts") { AlertsSettingsSection(io.github.neotheon3.orangebox.core.model.AlertPreferences()) {} }
}
