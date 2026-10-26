package io.github.neotheon3.orangebox.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    private fun shot(name: String, dark: Boolean = false, dir: String = "build/outputs/settings", content: @Composable () -> Unit) {
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) { Surface { Box(Modifier.padding(16.dp)) { content() } } }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("$dir/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun units() = shot("settings_units") { UnitsSettingsSection(UnitPreferences.forCountry("US")) {} }

    @Test fun unitsDark() = shot("settings_units_dark", dark = true) { UnitsSettingsSection(UnitPreferences()) {} }

    @Test fun alerts() = shot("settings_alerts") { AlertsSettingsSection(io.github.neotheon3.orangebox.core.model.AlertPreferences()) {} }

    @Test fun crash() = shot("settings_crash") {
        CrashSettingsSection(
            CrashSettingsState(io.github.neotheon3.orangebox.core.model.CrashPreferences(detectionEnabled = true, contactNumber = "+919876543210", contactName = "Asha")),
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

    // --- M9: account and sync (build/outputs/settings/m9/) ---

    private fun m9(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata")) // times as the user sees them
        try {
            shot(name, dark, "build/outputs/settings/m9", content)
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    private val remote = io.github.neotheon3.orangebox.core.model.BackendMode.REMOTE

    /** 26 Oct 2026, 09:41 IST. */
    private val at = 1_761_451_860_000L + 365L * 86_400_000

    @Test fun signInEmail() = m9("m9_sign_in_email") {
        AccountSettingsSection(AccountUiState(form = SignInForm(email = "kiran@example.com")), remote, true, AccountActions())
    }

    @Test fun signInEmailError() = m9("m9_sign_in_email_error", dark = true) {
        AccountSettingsSection(
            AccountUiState(form = SignInForm(email = "kiran@", error = io.github.neotheon3.orangebox.core.datastore.SignInError.INVALID_EMAIL)),
            remote, true, AccountActions(),
        )
    }

    @Test fun signInCode() = m9("m9_sign_in_code") {
        AccountSettingsSection(
            AccountUiState(form = SignInForm(step = SignInStep.CODE, email = "kiran@example.com", code = "4821", resendInSeconds = 42)),
            remote, true, AccountActions(),
        )
    }

    @Test fun signInCodeLocked() = m9("m9_sign_in_code_locked") {
        AccountSettingsSection(
            AccountUiState(
                form = SignInForm(
                    step = SignInStep.CODE, email = "kiran@example.com",
                    error = io.github.neotheon3.orangebox.core.datastore.SignInError.LOCKED, retryAfterSeconds = 900,
                ),
            ),
            remote, true, AccountActions(),
        )
    }

    @Test fun syncStatusOk() = m9("m9_sync_status_ok") {
        AccountSettingsSection(
            AccountUiState(
                io.github.neotheon3.orangebox.core.datastore.BackendAccountState(
                    signedIn = true, email = "kiran@example.com",
                    lastSync = io.github.neotheon3.orangebox.core.datastore.SyncStatus(at, ok = true, uploadedTrips = 3, pulledTrips = 1, telemetryBatches = 4),
                ),
            ),
            remote, true, AccountActions(),
        )
    }

    @Test fun syncStatusError() = m9("m9_sync_status_error", dark = true) {
        AccountSettingsSection(
            AccountUiState(
                io.github.neotheon3.orangebox.core.datastore.BackendAccountState(
                    signedIn = true, email = "kiran@example.com", syncing = true,
                    lastSync = io.github.neotheon3.orangebox.core.datastore.SyncStatus(
                        at, ok = false, error = io.github.neotheon3.orangebox.core.datastore.SyncError.SERVER_BUSY, willRetry = true,
                    ),
                ),
            ),
            remote, true, AccountActions(),
        )
    }

    @Test fun syncStatusFake() = m9("m9_sync_status_fake") {
        AccountSettingsSection(AccountUiState(), io.github.neotheon3.orangebox.core.model.BackendMode.FAKE, true, AccountActions())
    }

    @Test fun signOutDialog() {
        compose.setContent {
            OrangeBoxTheme {
                Surface(Modifier.fillMaxSize()) {
                    AccountSettingsSection(
                        AccountUiState(io.github.neotheon3.orangebox.core.datastore.BackendAccountState(signedIn = true, email = "kiran@example.com"), confirmSignOut = true),
                        remote, true, AccountActions(),
                    )
                }
            }
        }
        // The dialog is its own window: capture it, not the root behind it.
        val bitmap = compose.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File("build/outputs/settings/m9/m9_sign_out_dialog.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun serverRemoteSignedIn() = m9("m9_server_section_signed_in") {
        androidx.compose.foundation.layout.Column {
            ServerSettingsSection(
                SettingsUiState(
                    config = io.github.neotheon3.orangebox.core.model.BackendConfig(remote, "https://api.orangebox.example"),
                    urlDraft = "https://api.orangebox.example",
                ),
                {}, {}, {},
            )
            AccountSettingsSection(
                AccountUiState(
                    io.github.neotheon3.orangebox.core.datastore.BackendAccountState(
                        signedIn = true, email = "kiran@example.com",
                        lastSync = io.github.neotheon3.orangebox.core.datastore.SyncStatus(at, ok = true, uploadedTrips = 3, pulledTrips = 1),
                    ),
                ),
                remote, true, AccountActions(),
            )
        }
    }
}

