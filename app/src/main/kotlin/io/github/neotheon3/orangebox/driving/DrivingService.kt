package io.github.neotheon3.orangebox.driving

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.TripRecorder
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the drive alive while the dongle is connected: foreground service of type connectedDevice
 * (+ location when a location permission is granted), with the ongoing notification, and runs the
 * [TripRecorder]. Stops itself [DISCONNECT_GRACE_MILLIS] after the link goes away (the recorder closes
 * the trip after its own 30 s engine-off grace), or when the drive is ended ([DrivingController] stops it).
 */
@AndroidEntryPoint
class DrivingService : Service() {
    @Inject lateinit var connection: ObdConnection
    @Inject lateinit var recorder: TripRecorder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            recorder.start()
            watch()
        }
        // Not sticky: after process death the drive restarts from the next dongle connect.
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        DrivingNotifications.ensureChannels(this)
        val notification = DrivingNotifications.ongoing(this, recorder.active.value)
        val location = hasLocationPermission()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        return try {
            ServiceCompat.startForeground(this, DrivingNotifications.ID_ONGOING, notification, types)
            OrangeLog.i(OrangeLog.Areas.APP, "driving_service_foreground", "location" to location)
            true
        } catch (e: SecurityException) {
            // e.g. location type while the app is not in use: fall back to connectedDevice only.
            if (location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ServiceCompat.startForeground(this, DrivingNotifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    OrangeLog.i(OrangeLog.Areas.APP, "driving_service_foreground", "location" to false)
                    return true
                } catch (inner: SecurityException) {
                    OrangeLog.w(OrangeLog.Areas.APP, "driving_service_failed", "reason" to "security_exception", error = inner)
                    return false
                }
            }
            OrangeLog.w(OrangeLog.Areas.APP, "driving_service_failed", "reason" to "security_exception", error = e)
            false
        } catch (e: IllegalStateException) {
            OrangeLog.w(OrangeLog.Areas.APP, "driving_service_failed", "reason" to "not_allowed", error = e)
            false
        }
    }

    private fun watch() {
        scope.launch {
            combine(connection.state, recorder.active) { c, t -> c to t }.collect { (_, trip) ->
                if (DrivingNotifications.canPost(this@DrivingService)) {
                    try {
                        NotificationManagerCompat.from(this@DrivingService)
                            .notify(DrivingNotifications.ID_ONGOING, DrivingNotifications.ongoing(this@DrivingService, trip))
                    } catch (_: SecurityException) { }
                }
            }
        }
        scope.launch {
            connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged().collectLatest { up ->
                if (!up) {
                    delay(DISCONNECT_GRACE_MILLIS)
                    stop("disconnected")
                }
            }
        }
    }

    private fun stop(reason: String) {
        OrangeLog.i(OrangeLog.Areas.APP, "driving_service_stop", "reason" to reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        /** Longer than the recorder's engine-off grace, so the trip is closed before the service goes. */
        const val DISCONNECT_GRACE_MILLIS = 60_000L

        fun startIntent(context: Context) = Intent(context, DrivingService::class.java)
    }
}
