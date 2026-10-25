package io.github.neotheon3.orangebox.core.obd

import kotlinx.coroutines.delay

/**
 * Scripted [ObdTransport] for tests. [responder] maps each written command to a response, or
 * null for "no prompt" (which becomes a [ObdError.Timeout] after the requested timeout in virtual time).
 */
class FakeObdTransport(
    var openError: ObdError? = null,
    var responder: (String) -> String? = { defaultResponse(it) },
) : ObdTransport {
    val written = mutableListOf<String>()
    var openCount = 0
        private set
    var closeCount = 0
        private set
    var isOpen = false
        private set
    /** When set, the next write fails with this error (e.g. to simulate a mid-session drop). */
    var writeError: ObdError? = null
    /** Virtual-time round trip added to every reply, to model a real Bluetooth dongle (#193). */
    var latencyMillis: Long = 0
    private var pending: String? = null

    override suspend fun open() {
        openCount++
        openError?.let { throw it }
        isOpen = true
    }

    override suspend fun writeLine(line: String) {
        writeError?.let { throw it }
        written += line
        pending = responder(line)
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String {
        val r = pending
        pending = null
        if (latencyMillis > 0) delay(latencyMillis)
        if (r == null) {
            delay(timeoutMillis)
            throw ObdError.Timeout()
        }
        return r
    }

    override suspend fun close() {
        closeCount++
        isOpen = false
    }

    companion object {
        /** A healthy CAN vehicle with spaces/headers off. */
        fun defaultResponse(cmd: String): String = when (cmd) {
            "ATZ" -> "\r\rELM327 v1.5"
            "ATDPN" -> "A6"
            "ATRV" -> "12.6V"
            "0100" -> "SEARCHING...\r4100BE3EB811"
            "010C" -> "410C1AF8"
            else -> if (cmd.startsWith("AT")) "OK" else "NO DATA"
        }
    }
}
