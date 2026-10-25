package io.github.neotheon3.orangebox

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.neotheon3.orangebox.driving.DrivingNotifications

/**
 * Launcher shortcut "Drive" (#192): long-press the app icon (or pin it) to go straight to the driving
 * dashboard. Published as a dynamic shortcut rather than a static `shortcuts.xml` entry because a static
 * intent needs a hard-coded package, and debug builds use the `.debug` application id.
 */
object LauncherShortcuts {
    const val DRIVE_ID = "drive"

    fun driveIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(DrivingNotifications.EXTRA_OPEN_DASHBOARD)
        .putExtra(DrivingNotifications.EXTRA_OPEN_DASHBOARD, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun drive(context: Context): ShortcutInfoCompat = ShortcutInfoCompat.Builder(context, DRIVE_ID)
        .setShortLabel(context.getString(R.string.shortcut_drive_short))
        .setLongLabel(context.getString(R.string.shortcut_drive_long))
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_drive))
        .setIntent(driveIntent(context))
        .build()

    /** Idempotent; cheap enough to call on every launch. Never throws (launchers may refuse shortcuts). */
    fun publish(context: Context) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, drive(context)) }
    }
}
