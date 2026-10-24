package io.github.neotheon3.orangebox.driving

import android.Manifest
import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [26, 34])
class DrivingServiceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun inject() = hilt.inject()

    @Test fun `starts in the foreground with the ongoing driving notification`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(DrivingService::class.java).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        val n = shadow.lastForegroundNotification
        assertEquals(DrivingNotifications.ID_ONGOING, shadow.lastForegroundNotificationId)
        assertEquals("OBD dongle connected", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(shadow.isStoppedBySelf)
        controller.destroy()
    }

    @Test fun `a refused foreground start stops the service instead of crashing`() {
        val controller = Robolectric.buildService(DrivingService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("missing connectedDevice prerequisite"))
        controller.startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    private val nm get() = app.getSystemService(android.app.NotificationManager::class.java)

    private fun startedService() = Robolectric.buildService(DrivingService::class.java).create().startCommand(0, 1).also {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A re-post queued just before the stop (the #137 leak) must not survive any stop path. */
    private fun simulateLateRepost() {
        androidx.core.app.NotificationManagerCompat.from(app)
            .notify(DrivingNotifications.ID_ONGOING, DrivingNotifications.ongoing(app, null))
        assertTrue(shadowOf(nm).activeNotifications.any { it.id == DrivingNotifications.ID_ONGOING })
    }

    @Test fun `End driving (stopService) removes notification 1001`() {
        val controller = startedService()
        simulateLateRepost()
        controller.destroy()
        assertTrue(shadowOf(nm).activeNotifications.none { it.id == DrivingNotifications.ID_ONGOING })
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
    }

    @Test fun `a refused start leaves no notification 1001 behind`() {
        simulateLateRepost()
        val controller = Robolectric.buildService(DrivingService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("refused"))
        controller.startCommand(0, 1)
        assertTrue(shadowOf(nm).activeNotifications.none { it.id == DrivingNotifications.ID_ONGOING })
    }

    @Test fun `controller-side cancel clears a leftover ongoing notification`() {
        simulateLateRepost()
        DrivingNotifications.cancelOngoing(app)
        assertTrue(shadowOf(nm).activeNotifications.none { it.id == DrivingNotifications.ID_ONGOING })
    }
}
