package io.github.neotheon3.orangebox.feature.setup

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class PermissionResolverTest {
    @Test fun `status follows granted, asked and rationale`() {
        assertEquals(PermissionStatus.GRANTED, PermissionResolver.resolve(granted = true, askedBefore = true, showRationale = false))
        assertEquals(PermissionStatus.NOT_ASKED, PermissionResolver.resolve(granted = false, askedBefore = false, showRationale = false))
        assertEquals(PermissionStatus.DENIED, PermissionResolver.resolve(granted = false, askedBefore = true, showRationale = true))
        assertEquals(PermissionStatus.PERMANENTLY_DENIED, PermissionResolver.resolve(granted = false, askedBefore = true, showRationale = false))
    }

    @Test fun `each permission is only asked on the levels that need it`() {
        assertEquals(listOf(AppPermission.LOCATION), AppPermission.forSdk(24))
        assertEquals(listOf(AppPermission.BLUETOOTH, AppPermission.LOCATION), AppPermission.forSdk(31))
        assertEquals(AppPermission.entries.toList(), AppPermission.forSdk(33))
        assertEquals(AppPermission.entries.toList(), AppPermission.forSdk(34))
    }
}

@RunWith(RobolectricTestRunner::class)
class PermissionsOnDeviceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test @Config(sdk = [24]) fun `api 24 needs no bluetooth or notification prompt`() {
        assertEquals(listOf(AppPermission.LOCATION), AppPermission.forDevice())
    }

    @Test @Config(sdk = [31]) fun `api 31 asks for nearby devices`() {
        assertEquals(listOf(AppPermission.BLUETOOTH, AppPermission.LOCATION), AppPermission.forDevice())
        assertFalse(PermissionResolver.isGranted(app, AppPermission.BLUETOOTH))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(PermissionResolver.isGranted(app, AppPermission.BLUETOOTH))
    }

    @Test @Config(sdk = [33]) fun `api 33 adds notifications`() {
        assertTrue(AppPermission.NOTIFICATIONS in AppPermission.forDevice())
        assertEquals(Build.VERSION_CODES.TIRAMISU, Build.VERSION.SDK_INT)
    }

    @Test @Config(sdk = [34]) fun `coarse location alone counts as granted`() {
        assertFalse(PermissionResolver.isGranted(app, AppPermission.LOCATION))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(PermissionResolver.isGranted(app, AppPermission.LOCATION))
        assertEquals(PermissionStatus.GRANTED, PermissionResolver.status(null, app, AppPermission.LOCATION, emptySet()))
    }

    @Test @Config(sdk = [34]) fun `settings intent targets this package`() {
        val intent = PermissionResolver.appSettingsIntent(app)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.data?.schemeSpecificPart)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `denied-forever rows offer settings, the others offer allow`() {
        val requested = mutableListOf<AppPermission>()
        var settings = 0
        compose.setContent {
            OrangeBoxTheme {
                PermissionsPanel(
                    rows = listOf(
                        PermissionRow(AppPermission.BLUETOOTH, PermissionStatus.NOT_ASKED),
                        PermissionRow(AppPermission.NOTIFICATIONS, PermissionStatus.GRANTED),
                        PermissionRow(AppPermission.LOCATION, PermissionStatus.PERMANENTLY_DENIED),
                    ),
                    onRequest = { requested += it },
                    onOpenSettings = { settings++ },
                )
            }
        }
        compose.onNodeWithTag("permission_allow_bluetooth").performClick()
        compose.onNodeWithTag("permission_allow_notifications").assertDoesNotExist()
        compose.onNodeWithTag("permission_settings_location").performClick()
        compose.onNodeWithTag("permissions_settings_help").assertIsDisplayed()
        assertEquals(listOf(AppPermission.BLUETOOTH), requested)
        assertEquals(1, settings)
    }

    @Test fun `empty list explains nothing is needed`() {
        compose.setContent { OrangeBoxTheme { PermissionsPanel(rows = emptyList(), onRequest = {}, onOpenSettings = {}) } }
        compose.onNodeWithTag("permissions_none").assertIsDisplayed()
    }
}
