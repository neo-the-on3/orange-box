package io.github.neotheon3.orangebox.core.model

/**
 * Crash (impact) detection while driving (M7). Nothing is ever called or sent automatically: a detected
 * impact shows a countdown prompt, and only the phone's own dialer / SMS app is opened, prefilled.
 */
data class CrashPreferences(
    /** Watch the accelerometer while driving. Off by default; the driver turns it on in Settings. */
    val detectionEnabled: Boolean = false,
    /** Impact threshold, g (total acceleration magnitude, gravity included). */
    val thresholdG: Double = DEFAULT_THRESHOLD_G,
    /** Seconds the prompt counts down before the dialer opens. */
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_S,
    /** Emergency contact for the "text my location" button (M8 #60), normalized; null = none. */
    val contactNumber: String? = null,
    /** Display name of [contactNumber], from the contact picker; null when typed in by hand. */
    val contactName: String? = null,
) {
    companion object {
        const val DEFAULT_THRESHOLD_G = 4.0
        const val MIN_THRESHOLD_G = 2.5
        const val MAX_THRESHOLD_G = 8.0
        const val DEFAULT_COUNTDOWN_S = 30
        const val MIN_COUNTDOWN_S = 10
        const val MAX_COUNTDOWN_S = 60

        fun clampThreshold(g: Double) = g.coerceIn(MIN_THRESHOLD_G, MAX_THRESHOLD_G)
        fun clampCountdown(s: Int) = s.coerceIn(MIN_COUNTDOWN_S, MAX_COUNTDOWN_S)

        const val MIN_CONTACT_DIGITS = 3
        const val MAX_CONTACT_DIGITS = 15

        private val SEPARATORS = Regex("[\\s().\\-/]")

        /**
         * A phone number as typed or picked, normalized for an `smsto:` link: spaces, dashes, dots,
         * slashes and brackets dropped; an optional leading `+` (or `00`, kept as is) then 3–15 digits
         * (E.164 allows 15). Returns null when it is not a usable number.
         */
        fun normalizeContactNumber(raw: String?): String? {
            val cleaned = raw?.trim()?.replace(SEPARATORS, "")?.takeIf { it.isNotEmpty() } ?: return null
            val digits = cleaned.removePrefix("+")
            if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
            if (digits.length !in MIN_CONTACT_DIGITS..MAX_CONTACT_DIGITS) return null
            return cleaned
        }
    }
}
