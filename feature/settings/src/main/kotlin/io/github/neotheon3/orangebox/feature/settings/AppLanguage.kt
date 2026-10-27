package io.github.neotheon3.orangebox.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * The app's language (#73), without AppCompat: on Android 13+ the system keeps a per-app locale (Settings → Apps →
 * Orange Box → Language), offered from the app's generated `localeConfig`. [current] is null when it follows the
 * system language. Older Android versions have no per-app language, so [of] returns null and the row is hidden.
 */
class AppLanguage(val current: String?, val open: () -> Unit) {
    companion object {
        fun of(context: Context): AppLanguage? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) forTiramisu(context) else null

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun forTiramisu(context: Context): AppLanguage {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            val current = locales?.takeUnless { it.isEmpty }?.get(0)?.let { it.getDisplayName(it).replaceFirstChar(Char::titlecase) }
            return AppLanguage(current) {
                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}
