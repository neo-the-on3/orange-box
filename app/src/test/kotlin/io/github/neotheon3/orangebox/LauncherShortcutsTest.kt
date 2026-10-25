package io.github.neotheon3.orangebox

import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.driving.DrivingNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `drive shortcut opens MainActivity with the open-dashboard extra (#192)`() {
        val shortcut = LauncherShortcuts.drive(context)
        assertEquals("Drive", shortcut.shortLabel)
        val intent = shortcut.intent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName) // follows the .debug application id
        assertTrue(intent.getBooleanExtra(DrivingNotifications.EXTRA_OPEN_DASHBOARD, false))
    }

    @Test fun `publish adds one dynamic shortcut and is idempotent`() {
        LauncherShortcuts.publish(context)
        LauncherShortcuts.publish(context)
        val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
        assertEquals(listOf(LauncherShortcuts.DRIVE_ID), ids)
    }
}
