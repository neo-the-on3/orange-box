package io.github.neotheon3.orangebox.core.model

/**
 * Crash (impact) detection while driving (M7). Nothing is ever called or sent automatically: a detected
 * impact shows a countdown prompt, and only the phone's own dialer / SMS app is opened, prefilled.
 */
data class CrashPreferences(
    /** Watch the accelerometer while driving. On by default (it only ever opens a prompt). */
    val detectionEnabled: Boolean = true,
    /** Impact threshold, g (total acceleration magnitude, gravity included). */
    val thresholdG: Double = DEFAULT_THRESHOLD_G,
    /** Seconds the prompt counts down before the dialer opens. */
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_S,
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
    }
}
