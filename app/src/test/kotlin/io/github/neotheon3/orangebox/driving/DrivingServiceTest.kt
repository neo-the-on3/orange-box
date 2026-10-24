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
}
