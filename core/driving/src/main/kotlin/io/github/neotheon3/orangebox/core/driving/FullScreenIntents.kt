package io.github.neotheon3.orangebox.core.driving

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings

/** Full-screen notification permission (USE_FULL_SCREEN_INTENT), which the user can revoke on API 34+. */
object FullScreenIntents {
    /** API 34+ asks the platform; below that the manifest permission is enough. */
    fun canUse(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }

    /** The system page where the user allows it (API 34+), else the app's notification (API 26+) or app details page. */
    fun settingsIntent(context: Context): Intent =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, ("package:" + context.packageName).toUri())
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + context.packageName).toUri())
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
