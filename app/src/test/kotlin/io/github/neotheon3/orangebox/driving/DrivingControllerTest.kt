package io.github.neotheon3.orangebox.driving

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.MainActivity
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.driving.DriveSession
import io.github.neotheon3.orangebox.core.driving.LocationSource
import io.github.neotheon3.orangebox.core.driving.TripRecorder
import io.github.neotheon3.orangebox.core.model.AppPreferences
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Plain [Application]: OrangeBoxApplication would install the app's own controller, whose first "not
 * connected" emission cancels the Start driving notification on another thread and races these checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DrivingControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val conn = FakeObdConnection()

    private object NoTrips : TripRepository {
        override val trips = MutableStateFlow<List<TripEntity>>(emptyList())
        override suspend fun start(trip: TripEntity) = trip.copy(id = 1)
        override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) = Unit
        override suspend fun closeOrphans() = 0
        override suspend fun trip(id: Long): TripEntity? = null
        override suspend fun samples(tripId: Long) = emptyList<TripSampleEntity>()
        override suspend fun events(tripId: Long) = emptyList<TripEventEntity>()
    }

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    @After fun stop() = scope.cancel()

    private fun controller(autoShow: Boolean): DrivingController {
        val prefs = FakeAppPreferencesRepository(AppPreferences(dashboard = DashboardPreferences(autoShowDashboard = autoShow)))
        val recorder = TripRecorder(scope, conn, FakeTelemetrySource(), NoTrips, LocationSource { emptyFlow() })
        return DrivingController(app, scope, conn, recorder, DriveSession(scope, conn), prefs).also { it.install(app) }
    }

    private fun connect() {
        conn.state.value = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun drainStartedActivities(): List<android.content.Intent> =
        generateSequence { shadowOf(app).nextStartedActivity }.toList()

    @Test fun `auto-show on with the app on screen brings the dashboard to the front over the lock screen`() {
        controller(autoShow = true)
        Robolectric.buildActivity(Activity::class.java).setup()
        drainStartedActivities()
        connect()
        val started = drainStartedActivities().single()
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra(DrivingNotifications.EXTRA_OPEN_DASHBOARD, false))
        assertTrue(started.getBooleanExtra(DrivingNotifications.EXTRA_SHOW_WHEN_LOCKED, false))
        assertTrue("on screen and the service started: no notification", shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `auto-show off with the app on screen opens nothing`() {
        controller(autoShow = false)
        Robolectric.buildActivity(Activity::class.java).setup()
        drainStartedActivities()
        connect()
        assertTrue(drainStartedActivities().isEmpty())
    }

    @Test fun `auto-show on in the background without the full-screen permission posts the notification with a settings link`() {
        controller(autoShow = true)
        connect()
        assertTrue("never starts an activity from the background", drainStartedActivities().isEmpty())
        val n = shadowOf(nm).allNotifications.single { it.channelId == DrivingNotifications.CHANNEL_DRIVE_START }
        assertNull(n.fullScreenIntent) // Robolectric's API 34 NotificationManager refuses full-screen intents.
        assertNotNull(n.actions.singleOrNull { it.title.toString() == "Allow full screen" })
    }

    @Config(sdk = [33])
    @Test fun `auto-show on in the background uses the full-screen intent where allowed`() {
        controller(autoShow = true)
        connect()
        val n = shadowOf(nm).allNotifications.single { it.channelId == DrivingNotifications.CHANNEL_DRIVE_START }
        assertNotNull(n.fullScreenIntent)
        assertTrue(shadowOf(n.fullScreenIntent).savedIntent.getBooleanExtra(DrivingNotifications.EXTRA_SHOW_WHEN_LOCKED, false))
    }

    @Config(sdk = [33])
    @Test fun `auto-show off in the background posts a plain notification`() {
        controller(autoShow = false)
        connect()
        val n = shadowOf(nm).allNotifications.single { it.channelId == DrivingNotifications.CHANNEL_DRIVE_START }
        assertNull(n.fullScreenIntent)
        assertEquals(1, n.actions.size)
    }
}
