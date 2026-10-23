package io.github.neotheon3.orangebox

import android.util.Log
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
            OrangeLog.sink = AndroidLogSink(if (debug) OrangeLog.Level.DEBUG else OrangeLog.Level.WARN)
        }
    }
}
