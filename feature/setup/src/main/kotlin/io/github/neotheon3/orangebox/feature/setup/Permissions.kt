package io.github.neotheon3.orangebox.feature.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permissions Orange Box asks for, each only on the SDK levels that need it:
 * - Bluetooth connect: runtime on API 31+ (24–30 use the install-time BLUETOOTH permission).
 * - Notifications: runtime on API 33+ (the driving notification arrives in M4).
 * - Location: runtime on every supported level; optional, for the trip route (M4/M5). Fine and
 *   coarse are requested together so the user can choose approximate.
 */
enum class AppPermission(val permissions: List<String>, val minSdk: Int, val required: Boolean) {
    BLUETOOTH(listOf(Manifest.permission.BLUETOOTH_CONNECT), Build.VERSION_CODES.S, required = true),
    NOTIFICATIONS(listOf(Manifest.permission.POST_NOTIFICATIONS), Build.VERSION_CODES.TIRAMISU, required = false),
    LOCATION(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), Build.VERSION_CODES.M, required = false),
    ;

    companion object {
        fun forSdk(sdk: Int): List<AppPermission> = entries.filter { sdk >= it.minSdk }
        fun forDevice(): List<AppPermission> = forSdk(Build.VERSION.SDK_INT)
    }
}

enum class PermissionStatus { GRANTED, NOT_ASKED, DENIED, PERMANENTLY_DENIED }

object PermissionResolver {
    /**
     * After a denial Android stops showing the dialog once "show rationale" turns false again; that's
     * the permanently-denied state, where only the app's system settings page can grant it.
     */
    fun resolve(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): PermissionStatus = when {
        granted -> PermissionStatus.GRANTED
        !askedBefore -> PermissionStatus.NOT_ASKED
        showRationale -> PermissionStatus.DENIED
        else -> PermissionStatus.PERMANENTLY_DENIED
    }

    /** Location counts as granted with either fine or coarse. */
    fun isGranted(context: Context, permission: AppPermission): Boolean = permission.permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun status(activity: Activity?, context: Context, permission: AppPermission, asked: Set<String>): PermissionStatus =
        resolve(
            granted = isGranted(context, permission),
            askedBefore = permission.permissions.any { it in asked },
            showRationale = activity != null && permission.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) },
        )

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
