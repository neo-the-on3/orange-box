package io.github.neotheon3.orangebox.core.obd

sealed interface ObdConnectionState {
    /** Not connected and not trying. */
    data object Idle : ObdConnectionState

    /**
     * Opening the link. [retry] is 0 for the first attempt, then 1..[maxRetries].
     */
    data class Connecting(val retry: Int, val maxRetries: Int) : ObdConnectionState

    /** Link open; running AT init and the `0100` probe. */
    data class Initializing(val retry: Int, val maxRetries: Int) : ObdConnectionState

    /** Waiting before the next retry. [secondsRemaining] counts down once per second. */
    data class WaitingToRetry(
        val nextRetry: Int,
        val maxRetries: Int,
        val secondsRemaining: Int,
        val lastError: ObdError,
    ) : ObdConnectionState

    data class Connected(val info: Elm327Info) : ObdConnectionState

    /** Gave up. Stays here until [ObdConnection.retry]. */
    data class Failed(val reason: ObdError, val attempts: Int) : ObdConnectionState
}

/** What UI and the telemetry engine use; implemented by [ObdConnectionManager]. */
interface ObdConnection {
    val state: kotlinx.coroutines.flow.StateFlow<ObdConnectionState>

    /** Starts connecting if Idle or Failed. No-op while connecting or connected. */
    fun connect()

    /** From Failed (or any state): drops the current attempt and restarts at the first attempt. */
    fun retry()

    fun disconnect()
}
