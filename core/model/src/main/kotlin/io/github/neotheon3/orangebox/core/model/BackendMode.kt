package io.github.neotheon3.orangebox.core.model

/**
 * How the app talks to the (optional) connected-car backend.
 *
 * The app must be fully usable in [OFF], which is the default.
 */
enum class BackendMode(val wireName: String) {
    /** Local only. No network requests are made. */
    OFF("off"),

    /** Canned in-process responses. No network requests are made. */
    FAKE("fake"),

    /** Real HTTPS server at [BackendConfig.baseUrl]. */
    REMOTE("remote"),
    ;

    companion object {
        val DEFAULT = OFF

        /**
         * Parses a user/intent supplied value (case-insensitive, surrounding whitespace ignored).
         * Returns `null` for anything unrecognised so callers can fall back to the saved mode.
         */
        fun parse(value: String?): BackendMode? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}
