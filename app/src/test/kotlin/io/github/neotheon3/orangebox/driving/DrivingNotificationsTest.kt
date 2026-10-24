package io.github.neotheon3.orangebox.driving

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.MainActivity
import io.github.neotheon3.orangebox.core.driving.ActiveTrip
import io.github.neotheon3.orangebox.core.telemetry.TripTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34])
class DrivingNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nm = context.getSystemService(NotificationManager::class.java)

    @org.junit.Before fun grant() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun `without POST_NOTIFICATIONS on API 33+ nothing is posted`() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        DrivingNotifications.postDriveStart(context, fullScreen = false)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    private fun posted(): Notification = shadowOf(nm).allNotifications.single()

    /** The PendingIntent must open MainActivity with the dashboard extra (never start it ourselves). */
    private fun assertOpensDashboard(pi: android.app.PendingIntent) {
        val intent = shadowOf(pi).savedIntent
        assertTrue(shadowOf(pi).isActivityIntent)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(DrivingNotifications.EXTRA_OPEN_DASHBOARD, false))
    }

    @Test fun `start driving notification opens the dashboard from its tap and its action`() {
        DrivingNotifications.ensureChannels(context)
        DrivingNotifications.postDriveStart(context, fullScreen = false)
        val n = posted()
        assertEquals(DrivingNotifications.CHANNEL_DRIVE_START, n.channelId)
        assertOpensDashboard(n.contentIntent)
        assertEquals("Start driving", n.actions.single().title.toString())
        assertOpensDashboard(n.actions.single().actionIntent)
        assertNull(n.fullScreenIntent)
    }

    @Test fun `full-screen intent only when allowed`() {
        DrivingNotifications.ensureChannels(context)
        DrivingNotifications.postDriveStart(context, fullScreen = true)
        assertNotNull(posted().fullScreenIntent)
        assertOpensDashboard(posted().fullScreenIntent)
    }

    @Config(sdk = [33]) @Test fun `API 33 may use the full-screen intent from the manifest permission`() {
        assertTrue(DrivingNotifications.canUseFullScreenIntent(context))
    }

    @Config(sdk = [34]) @Test fun `API 34 asks the platform, which refuses by default`() {
        // Robolectric's NotificationManager answers false, like a device where the user has not allowed it.
        assertFalse(DrivingNotifications.canUseFullScreenIntent(context))
        DrivingNotifications.ensureChannels(context)
        DrivingNotifications.postDriveStart(context)
        assertNull(posted().fullScreenIntent)
    }

    @Test fun `no notification permission posts nothing`() {
        shadowOf(nm).setNotificationsEnabled(false)
        DrivingNotifications.postDriveStart(context, fullScreen = true)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `ongoing notification shows the recording trip`() {
        DrivingNotifications.ensureChannels(context)
        val idle = DrivingNotifications.ongoing(context, null)
        assertEquals("OBD dongle connected", idle.extras.getString(Notification.EXTRA_TITLE))
        val rec = DrivingNotifications.ongoing(context, ActiveTrip(1, 0, TripTotals(durationS = 3_725.0, distanceKm = 12.34)))
        assertEquals("Recording trip", rec.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("12.3 km · 1:02:05", rec.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(rec.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertOpensDashboard(rec.contentIntent)
        assertEquals(DrivingNotifications.CHANNEL_DRIVING, rec.channelId)
    }
}
