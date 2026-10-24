package io.github.neotheon3.orangebox.driving

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.DriveSession
import io.github.neotheon3.orangebox.core.driving.TripRecorder
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-start wiring: when the dongle connects, start [DrivingService]; when the app is not on screen
 * (or the platform refuses a background service start), post the "Start driving" notification instead
 * of opening anything.
 *
 * With "Show dashboard automatically" on, the dashboard comes to the front when the app is on screen
 * (over the lock screen, waking it), and from the background the notification carries a full-screen
 * intent when the platform allows it (otherwise an "Allow full screen" action). The keyguard is never
 * dismissed.
 *
 * The recorder runs whenever connected; the service keeps the process (and the notification) alive.
 * Ending the drive (after its undo window) closes the trip and stops the service.
 */
@Singleton
class DrivingController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val recorder: TripRecorder,
    private val session: DriveSession,
    prefs: AppPreferencesRepository,
) {
    private val autoShow = prefs.preferences.map { it.dashboard.autoShowDashboard }
        .stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile private var startedActivities = 0
    val inForeground: Boolean get() = startedActivities > 0

    fun install(app: Application) {
        DrivingNotifications.ensureChannels(context)
        app.registerActivityLifecycleCallbacks(ForegroundTracker())
        session.addOnEndListener {
            scope.launch {
                recorder.stop(TripRecorder.END_USER)
                context.stopService(DrivingService.startIntent(context))
            }
        }
        scope.launch {
            connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged().collect { up ->
                if (up) onConnected() else DrivingNotifications.cancelDriveStart(context)
            }
        }
    }

    private fun onConnected() {
        recorder.start()
        val serviceStarted = startService()
        val show = autoShow.value
        if (inForeground && show) bringDashboardToFront()
        if (!serviceStarted || !inForeground) DrivingNotifications.postDriveStart(context, autoShow = show)
    }

    /** "Show dashboard automatically" with the app on screen: an activity start from the foreground is allowed. */
    private fun bringDashboardToFront() {
        try {
            context.startActivity(DrivingNotifications.dashboardActivityIntent(context, showWhenLocked = true))
            OrangeLog.i(OrangeLog.Areas.APP, "dashboard_auto_shown")
        } catch (e: RuntimeException) {
            OrangeLog.w(OrangeLog.Areas.APP, "dashboard_auto_show_failed", error = e)
        }
    }

    private fun startService(): Boolean =
        try {
            ContextCompat.startForegroundService(context, DrivingService.startIntent(context))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) from the background.
            OrangeLog.w(OrangeLog.Areas.APP, "driving_service_start_refused", error = e)
            false
        }

    private inner class ForegroundTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (connection.state.value is ObdConnectionState.Connected) {
                // Back on screen (e.g. from "Start driving"): a refused background start can succeed now.
                DrivingNotifications.cancelDriveStart(context)
                recorder.start()
                startService()
            }
        }
        override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
