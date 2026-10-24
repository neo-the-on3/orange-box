package io.github.neotheon3.orangebox.core.driving

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A launchable app for the dashboard's quick launcher. */
data class LaunchableApp(val packageName: String, val label: String, val icon: Bitmap?)

/**
 * Apps with a launcher activity, found through the manifest `<queries>` MAIN/LAUNCHER intent
 * (package visibility without QUERY_ALL_PACKAGES).
 */
class QuickLaunchApps @Inject constructor(@ApplicationContext private val context: Context) {
    private val launcherIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** Every launchable app except Orange Box itself, by label. */
    fun list(): List<LaunchableApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, info) -> LaunchableApp(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm)?.toIconBitmap()) }
            .sortedBy { it.label.lowercase() }
    }

    /** The app if it is still installed and launchable, else null. */
    fun find(packageName: String): LaunchableApp? {
        val pm = context.packageManager
        val info = pm.queryIntentActivities(Intent(launcherIntent).setPackage(packageName), 0).firstOrNull() ?: return null
        return LaunchableApp(packageName, info.loadLabel(pm).toString(), info.loadIcon(pm)?.toIconBitmap())
    }

    /** Starts [packageName]'s launcher activity; false if it is gone. Call from the foreground only. */
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun Drawable.toIconBitmap(): Bitmap? = runCatching { toBitmap(ICON_PX, ICON_PX) }.getOrNull()

    private companion object { const val ICON_PX = 96 }
}
