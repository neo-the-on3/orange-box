package io.github.neotheon3.orangebox

import android.util.Log
import io.github.neotheon3.orangebox.core.common.ConnectionLog
import io.github.neotheon3.orangebox.core.common.OrangeLog

/**
 * Routes [OrangeLog] to logcat. Debug builds log everything from DEBUG and show sensitive values.
 * Release builds keep only WARN/ERROR and redact sensitive values (VIN, device addresses).
 */
class AndroidLogSink(private val minLevel: OrangeLog.Level) : OrangeLog.Sink {
    override fun log(level: OrangeLog.Level, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val priority = when (level) {
            OrangeLog.Level.DEBUG -> Log.DEBUG
            OrangeLog.Level.INFO -> Log.INFO
            OrangeLog.Level.WARN -> Log.WARN
            OrangeLog.Level.ERROR -> Log.ERROR
        }
        val text = if (error != null) message + "\n" + Log.getStackTraceString(error) else message
        Log.println(priority, tag, text)
    }

    companion object {
        fun install(debug: Boolean) {
            OrangeLog.allowSensitive = debug
            val logcat = AndroidLogSink(if (debug) OrangeLog.Level.DEBUG else OrangeLog.Level.WARN)
            // Debug builds also keep the connection-related lines in ConnectionLog (Export connection log).
            ConnectionLog.enabled = debug
            OrangeLog.sink = if (!debug) logcat else OrangeLog.Sink { level, tag, message, error ->
                logcat.log(level, tag, message, error)
                if (tag in CONNECTION_TAGS) ConnectionLog.record("LOG", "${level.name[0]} $tag $message" + (error?.let { " error=${it::class.simpleName}(${it.message})" } ?: ""))
            }
        }

        private val CONNECTION_TAGS = setOf(
            OrangeLog.tag(OrangeLog.Areas.OBD), OrangeLog.tag(OrangeLog.Areas.BLUETOOTH),
            OrangeLog.tag(OrangeLog.Areas.TELEMETRY), OrangeLog.tag(OrangeLog.Areas.TRIP), OrangeLog.tag(OrangeLog.Areas.APP),
        )
    }
}
