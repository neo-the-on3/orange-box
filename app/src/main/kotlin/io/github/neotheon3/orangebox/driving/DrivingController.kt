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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-start wiring: when the dongle connects, start [DrivingService]; when the app is not on screen
 * (or the platform refuses a background service start), post the "Start driving" notification instead
 * of opening anything. The recorder runs whenever connected; the service keeps the process (and the
 * notification) alive. Ending the drive (after its undo window) closes the trip and stops the service.
 */
@Singleton
class DrivingController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val recorder: TripRecorder,
    private val session: DriveSession,
) {
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
        if (!startService() || !inForeground) DrivingNotifications.postDriveStart(context)
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
