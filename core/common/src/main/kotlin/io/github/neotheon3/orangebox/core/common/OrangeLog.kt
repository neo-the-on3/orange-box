package io.github.neotheon3.orangebox.core.common

/**
 * Tiny structured-logging facade usable from pure-JVM modules. Tags are `OrangeBox/<area>`, e.g.
 * `OrangeBox/OBD`. The app installs an Android sink at startup; until then (and in JVM tests) logs go nowhere.
 *
 * Messages are `event key=value …` so they are easy to grep. Never pass personal data or the VIN as a
 * normal field: use [sensitive], which the release sink drops.
 */
object OrangeLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    const val TAG_PREFIX = "OrangeBox"

    @Volatile var sink: Sink = Sink { _, _, _, _ -> }

    /** False in release builds: [sensitive] values are replaced by `<redacted>`. */
    @Volatile var allowSensitive: Boolean = false

    fun tag(area: String) = "$TAG_PREFIX/$area"

    fun d(area: String, event: String, vararg fields: Pair<String, Any?>) = emit(Level.DEBUG, area, event, fields, null)
    fun i(area: String, event: String, vararg fields: Pair<String, Any?>) = emit(Level.INFO, area, event, fields, null)
    fun w(area: String, event: String, vararg fields: Pair<String, Any?>, error: Throwable? = null) =
        emit(Level.WARN, area, event, fields, error)
    fun e(area: String, event: String, vararg fields: Pair<String, Any?>, error: Throwable? = null) =
        emit(Level.ERROR, area, event, fields, error)

    /** Wraps a personal value (VIN, MAC address, …). Rendered only when [allowSensitive] is true. */
    fun sensitive(value: Any?): Sensitive = Sensitive(value)

    class Sensitive internal constructor(private val value: Any?) {
        override fun toString(): String = if (allowSensitive) value.toString() else "<redacted>"
    }

    fun format(event: String, fields: Array<out Pair<String, Any?>>): String =
        if (fields.isEmpty()) event else event + fields.joinToString(" ", prefix = " ") { (k, v) -> "$k=$v" }

    private fun emit(level: Level, area: String, event: String, fields: Array<out Pair<String, Any?>>, error: Throwable?) {
        sink.log(level, tag(area), format(event, fields), error)
    }

    object Areas {
        const val OBD = "OBD"
        const val BLUETOOTH = "BT"
        const val BACKEND = "Backend"
        const val TELEMETRY = "Telemetry"
        const val APP = "App"
        const val TRIP = "Trip"
    }
}
