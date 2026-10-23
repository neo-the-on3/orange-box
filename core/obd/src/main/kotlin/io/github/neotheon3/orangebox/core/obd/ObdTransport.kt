package io.github.neotheon3.orangebox.core.obd

/**
 * A line-oriented link to an ELM327-compatible adapter (Bluetooth SPP, the simulator, or a test fake).
 * Implementations must be safe to call from a single coroutine at a time; [Elm327Session] serialises access.
 */
interface ObdTransport {
    /** Opens the link. Throws [ObdError] on failure. */
    suspend fun open()

    /** Sends [line]; the transport appends the `\r` terminator. */
    suspend fun writeLine(line: String)

    /**
     * Reads until the ELM327 prompt `>` and returns everything before it (prompt excluded).
     * Throws [ObdError.Timeout] if no prompt arrives within [timeoutMillis].
     */
    suspend fun readUntilPrompt(timeoutMillis: Long): String

    /** Closes the link. Idempotent, never throws. */
    suspend fun close()
}

/** Creates a transport for the currently selected dongle. Throws [ObdError.NotPaired] if there is none. */
fun interface ObdTransportProvider {
    suspend fun create(): ObdTransport
}

/** Typed OBD failures. [isTerminal] errors are not retried automatically. */
sealed class ObdError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    open val isTerminal: Boolean get() = false

    /** Short, PII-free description for logs: type and message. */
    fun describe(): String = "${this::class.simpleName}(${message})"

    /** No dongle selected, or the selected device is not bonded. */
    class NotPaired(message: String = "No paired OBD dongle") : ObdError(message) {
        override val isTerminal get() = true
    }

    /** The adapter answered but could not talk to the vehicle (`UNABLE TO CONNECT`). */
    class UnsupportedProtocol(message: String = "Vehicle protocol not supported") : ObdError(message) {
        override val isTerminal get() = true
    }

    /** A runtime permission (e.g. BLUETOOTH_CONNECT) is missing. */
    class PermissionDenied(val permission: String) : ObdError("Missing permission $permission") {
        override val isTerminal get() = true
    }

    /** Link-level I/O failure (socket refused, connection dropped, ECU silent). */
    class Io(message: String, cause: Throwable? = null) : ObdError(message, cause)

    /** No prompt within the timeout. */
    class Timeout(message: String = "Adapter did not respond in time") : ObdError(message)
}
