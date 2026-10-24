package io.github.neotheon3.orangebox.driving

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.neotheon3.orangebox.MainActivity
import io.github.neotheon3.orangebox.R
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.ActiveTrip
import java.util.Locale

/**
 * Driving notifications. Two channels:
 * - [CHANNEL_DRIVING] (low): the ongoing foreground-service notification while connected / recording;
 * - [CHANNEL_DRIVE_START] (high): "Start driving" when the dongle connects while the app is not on screen.
 * Both open the dashboard via [dashboardIntent]; no activity is ever started from the background, and
 * the full-screen intent is only attached when [canUseFullScreenIntent] allows it.
 */
object DrivingNotifications {
    const val CHANNEL_DRIVING = "driving"
    const val CHANNEL_DRIVE_START = "drive_start"
    const val ID_ONGOING = 1001
    const val ID_DRIVE_START = 1002
    const val EXTRA_OPEN_DASHBOARD = "io.github.neotheon3.orangebox.OPEN_DASHBOARD"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DRIVING, context.getString(R.string.channel_driving), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_driving_desc); setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DRIVE_START, context.getString(R.string.channel_drive_start), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_drive_start_desc) },
        )
    }

    fun dashboardIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(EXTRA_OPEN_DASHBOARD)
            .putExtra(EXTRA_OPEN_DASHBOARD, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The foreground-service notification. [trip] null means connected but not recording yet. */
    fun ongoing(context: Context, trip: ActiveTrip?) = NotificationCompat.Builder(context, CHANNEL_DRIVING)
        .setSmallIcon(R.drawable.ic_stat_drive)
        .setContentTitle(context.getString(if (trip == null) R.string.notif_connected_title else R.string.notif_recording_title))
        .setContentText(
            if (trip == null) context.getString(R.string.notif_connected_text)
            else context.getString(
                R.string.notif_recording_text,
                String.format(Locale.getDefault(), "%.1f", trip.totals.distanceKm),
                elapsed(trip.totals.durationS),
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(dashboardIntent(context, 1))
        .addAction(0, context.getString(R.string.notif_start_driving), dashboardIntent(context, 2))
        .build()

    /** Heads-up "Start driving"; full-screen only where the platform lets us. */
    fun postDriveStart(context: Context, fullScreen: Boolean = canUseFullScreenIntent(context)) {
        if (!canPost(context)) {
            OrangeLog.i(OrangeLog.Areas.APP, "drive_start_notification_skipped", "reason" to "no_permission")
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DRIVE_START)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle(context.getString(R.string.notif_drive_start_title))
            .setContentText(context.getString(R.string.notif_drive_start_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(DRIVE_START_TIMEOUT_MILLIS)
            .setContentIntent(dashboardIntent(context, 3))
            .addAction(0, context.getString(R.string.notif_start_driving), dashboardIntent(context, 4))
        if (fullScreen) builder.setFullScreenIntent(dashboardIntent(context, 5), true)
        try {
            NotificationManagerCompat.from(context).notify(ID_DRIVE_START, builder.build())
            OrangeLog.i(OrangeLog.Areas.APP, "drive_start_notification", "full_screen" to fullScreen)
        } catch (e: SecurityException) {
            OrangeLog.w(OrangeLog.Areas.APP, "drive_start_notification_skipped", "reason" to "security_exception")
        }
    }

    fun cancelDriveStart(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DRIVE_START)

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** API 34+ asks the platform (the user can revoke it); below that the manifest permission is enough. */
    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }

    private fun elapsed(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private const val DRIVE_START_TIMEOUT_MILLIS = 10 * 60_000L
}
