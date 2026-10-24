package io.github.neotheon3.orangebox.feature.driving

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext

/** The bottom-left dashboard button, from Settings. */
sealed interface LeftButton {
    /** Emergency prompt that pre-fills [number] in the dialer. */
    data class Hazard(val number: String) : LeftButton

    /** Launches [packageName] (the user's chosen app) and shows its icon. */
    data class QuickLaunch(val packageName: String, val label: String, val icon: ImageBitmap?) : LeftButton
}

/** Settings-driven parts of the dashboard. */
data class DashboardControls(
    val leftButton: LeftButton = LeftButton.Hazard(EMERGENCY_NUMBER),
    /** "Show dashboard automatically": the dashboard may show over the lock screen and wake it. */
    val showWhenLocked: Boolean = false,
)

/**
 * While [enabled] and this is on screen, the activity shows over the keyguard and turns the screen on.
 * The keyguard is never dismissed: leaving the dashboard (which clears the flags) still needs an unlock.
 */
@Composable
fun ShowWhenLocked(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, enabled) {
        if (enabled) activity.setLockScreenFlags(true)
        onDispose { if (enabled) activity.setLockScreenFlags(false) }
    }
}

internal fun Activity.setLockScreenFlags(on: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(on)
        setTurnScreenOn(on)
    } else {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (on) window.addFlags(flags) else window.clearFlags(flags)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
