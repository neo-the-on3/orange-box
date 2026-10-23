package io.github.neotheon3.orangebox.core.model

/** Build identity shown in Settings → About. Provided by `:app` from BuildConfig. */
data class AppVersion(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    /** GitHub Actions run number, or null for a local build. */
    val ciRun: String?,
    val debug: Boolean,
) {
    val summary: String get() = "$versionName (code $versionCode)"

    companion object {
        val UNKNOWN = AppVersion("dev", 0, "unknown", null, debug = true)
    }
}
