package io.github.neotheon3.orangebox.feature.setup

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #121: "Open Bluetooth settings" used to swallow ActivityNotFoundException and do nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34])
class BluetoothSettingsIntentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun strict() {
        shadowOf(app).checkActivities(true) // startActivity throws ActivityNotFoundException when nothing resolves
    }

    private fun register(action: String) {
        val component = ComponentName("com.android.settings", "com.android.settings.${action.substringAfterLast('.')}")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory("android.intent.category.DEFAULT") })
    }

    @Test fun `opens bluetooth settings when the device has them`() {
        register(Settings.ACTION_BLUETOOTH_SETTINGS)
        register(Settings.ACTION_SETTINGS)
        assertTrue(openBluetoothSettings(app))
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `falls back to the main settings screen`() {
        register(Settings.ACTION_SETTINGS)
        assertTrue(openBluetoothSettings(app))
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `reports failure when nothing can be opened`() {
        assertFalse(openBluetoothSettings(app))
    }
}
