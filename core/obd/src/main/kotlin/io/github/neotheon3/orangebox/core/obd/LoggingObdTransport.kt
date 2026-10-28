package io.github.neotheon3.orangebox.core.obd

import io.github.neotheon3.orangebox.core.common.ConnectionLog

/**
 * Records every call on [delegate] in [ConnectionLog] (debug diagnostics). Behaviour is unchanged:
 * results and exceptions pass through untouched.
 *
 * A request and its reply share one line, `TR <request> > <reply> (<ms>ms)`, with the reply's line breaks
 * and prompt trimmed (#200: about half the size of separate TX and RX lines). A request without a reply
 * (write failure, read timeout) is logged as `TX <request>` followed by an `ERR` line. Each [open] starts a
 * new session header.
 */
class LoggingObdTransport(private val delegate: ObdTransport, private val label: String) : ObdTransport {
    @Volatile private var lastTxNanos = 0L
    /** The last request, waiting for its reply to be logged on the same line. */
    @Volatile private var pendingTx: String? = null

    private fun flushPending() {
        pendingTx?.let { ConnectionLog.record("TX", it) }
        pendingTx = null
    }

    override suspend fun open() {
        ConnectionLog.newSession(label)
        ConnectionLog.record("OPEN", "$label start")
        val t0 = System.nanoTime()
        try {
            delegate.open()
            ConnectionLog.record("OPEN", "$label ok ${ms(t0)}ms")
        } catch (e: Throwable) {
            ConnectionLog.record("OPEN", "$label failed ${ms(t0)}ms ${describe(e)}")
            throw e
        }
    }

    override suspend fun writeLine(line: String) {
        flushPending()
        lastTxNanos = System.nanoTime()
        pendingTx = line
        try {
            delegate.writeLine(line)
        } catch (e: Throwable) {
            flushPending()
            ConnectionLog.record("ERR", "write ${describe(e)}")
            throw e
        }
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String {
        try {
            val raw = delegate.readUntilPrompt(timeoutMillis)
            val tx = pendingTx
            pendingTx = null
            if (tx != null) ConnectionLog.record("TR", "$tx > ${ConnectionLog.reply(raw)} (${ms(lastTxNanos)}ms)")
            else ConnectionLog.record("RX", "${ConnectionLog.reply(raw)} (${ms(lastTxNanos)}ms)")
            return raw
        } catch (e: Throwable) {
            flushPending()
            ConnectionLog.record("ERR", "read after ${ms(lastTxNanos)}ms (timeout ${timeoutMillis}ms) ${describe(e)}")
            throw e
        }
    }

    override suspend fun close() {
        flushPending()
        ConnectionLog.record("CLOSE", label)
        delegate.close()
    }

    private fun ms(since: Long) = if (since == 0L) -1 else (System.nanoTime() - since) / 1_000_000

    private fun describe(e: Throwable) = if (e is ObdError) e.describe() else "${e::class.simpleName}(${e.message})"
}

/** Wraps in [LoggingObdTransport] only while [ConnectionLog] is enabled (debug builds). */
fun ObdTransport.loggedIfEnabled(label: String): ObdTransport =
    if (ConnectionLog.enabled) LoggingObdTransport(this, label) else this
