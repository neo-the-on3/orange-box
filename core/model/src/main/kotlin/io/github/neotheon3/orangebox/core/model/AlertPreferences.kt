package io.github.neotheon3.orangebox.core.model

/** The driving alerts Orange Box can sound (M8 #59). */
enum class AlertKind { OVERSPEED, IDLING, HARSH_ACCEL, HARSH_BRAKE, MIL }

/**
 * Which driving alerts play, and how. Each alert is toggled on its own; [sound] plays a short tone
 * (Orange Box's own sounds), [voice] also speaks it with the device's text-to-speech engine. Every alert,
 * long idling included (3 min standing still), is on by default; voice is off by default.
 */
data class AlertPreferences(
    val overspeed: Boolean = true,
    val idling: Boolean = true,
    val harsh: Boolean = true,
    val mil: Boolean = true,
    val sound: Boolean = true,
    val voice: Boolean = false,
) {
    fun isEnabled(kind: AlertKind): Boolean = when (kind) {
        AlertKind.OVERSPEED -> overspeed
        AlertKind.IDLING -> idling
        AlertKind.HARSH_ACCEL, AlertKind.HARSH_BRAKE -> harsh
        AlertKind.MIL -> mil
    }

    /** Whether anything at all would be played. */
    val audible: Boolean get() = (sound || voice) && AlertKind.entries.any(::isEnabled)
}
