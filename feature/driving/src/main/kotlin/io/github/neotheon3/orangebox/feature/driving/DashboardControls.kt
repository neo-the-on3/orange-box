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
import androidx.core.view.WindowCompat

/** Fallback number pre-filled in the dialer (112: India, the EU, and GSM phones elsewhere). Settings can change it. */
const val EMERGENCY_NUMBER = "112"

/** The bottom-left dashboard button, from Settings. */
sealed interface LeftButton {
    /** Opens the emergency prompt (manual mode), which pre-fills [number] in the dialer. */
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

/**
 * The dashboard is always dark, and since #145 the status-bar strip above the bezel arch is black, so
 * while it is on screen the status- and navigation-bar icons are forced light (white). The activity's
 * `enableEdgeToEdge()` picks dark icons when the system is in light mode, which would leave them
 * invisible (#149). The previous appearance is restored when the dashboard leaves. The flags live on
 * the activity window, so this also covers the dashboard shown over the lock screen.
 */
@Composable
fun LightSystemBarIcons() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previousStatus = controller.isAppearanceLightStatusBars
        val previousNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatus
            controller.isAppearanceLightNavigationBars = previousNavigation
        }
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
