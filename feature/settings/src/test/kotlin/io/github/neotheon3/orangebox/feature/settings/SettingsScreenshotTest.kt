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

    @Test fun crash() = shot("settings_crash") {
        CrashSettingsSection(
            CrashSettingsState(io.github.neotheon3.orangebox.core.model.CrashPreferences(contactNumber = "+919876543210", contactName = "Asha")),
            CrashSettingsActions(),
        )
    }

    @Test fun crashContactEntry() = shot("settings_crash_contact_entry", dark = true) {
        CrashSettingsSection(
            CrashSettingsState(io.github.neotheon3.orangebox.core.model.CrashPreferences(detectionEnabled = false), contactDraft = "12", contactError = true),
            CrashSettingsActions(),
        )
    }

    @Test fun server() = shot("settings_server_remote_not_https") {
        ServerSettingsSection(
            SettingsUiState(
                config = io.github.neotheon3.orangebox.core.model.BackendConfig(io.github.neotheon3.orangebox.core.model.BackendMode.REMOTE),
                urlDraft = "http://14.63.216.111:9801",
                urlResult = io.github.neotheon3.orangebox.core.model.BaseUrlValidator.Result.NotHttps,
            ),
            {}, {}, {},
        )
    }

    @Test fun drivingSection() = shot("settings_dashboard_and_drive_start") {
        androidx.compose.foundation.layout.Column {
            DashboardSettingsSection(DashboardSettingsState(), null, DashboardSettingsActions(), UnitPreferences.forCountry("US"))
            SwitchRow(R.string.settings_diagnostics_prompt, R.string.settings_diagnostics_prompt_summary, true, "d") {}
        }
    }
}
