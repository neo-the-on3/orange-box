package io.github.neotheon3.orangebox.core.obd

import io.github.neotheon3.orangebox.core.common.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection state machine:
 * `Idle → Connecting → Initializing → Connected`, with retries (2/4/8/16/30 s, 5 retries) on
 * recoverable errors, then `Failed` until [retry]. NotPaired, UnsupportedProtocol and
 * PermissionDenied are terminal: one attempt, no retries.
 *
 * While connected a heartbeat (`ATRV`) runs every [heartbeatMillis]; a failure, or a
 * [DongleLinkEvent.Disconnected] for the selected dongle, re-enters the retry loop.
 */
class ObdConnectionManager(
    private val scope: CoroutineScope,
    private val transportProvider: ObdTransportProvider,
    linkEvents: Flow<DongleLinkEvent> = emptyFlow(),
    private val policy: RetryPolicy = RetryPolicy(),
    private val heartbeatMillis: Long = 5_000,
    private val sessionFactory: (ObdTransport) -> Elm327Session = { Elm327Session(it) },
) : ObdConnection {
    private val _state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
    override val state: StateFlow<ObdConnectionState> = _state.asStateFlow()

    private val _lastLinkEvent = MutableStateFlow<DongleLinkEvent?>(null)
    val lastLinkEvent: StateFlow<DongleLinkEvent?> = _lastLinkEvent.asStateFlow()

    private var job: Job? = null
    private var session: Elm327Session? = null

    /** Number of transports opened since construction; handy for tests and the Simulator panel. */
    var totalAttempts: Int = 0
        private set

    init {
        scope.launch {
            linkEvents.collect { event ->
                _lastLinkEvent.value = event
                when (event) {
                    is DongleLinkEvent.Connected -> if (isIdleOrFailed()) connect()
                    is DongleLinkEvent.Disconnected -> if (_state.value is ObdConnectionState.Connected) restart(fromDrop = true)
                }
            }
        }
    }

    private fun isIdleOrFailed() = _state.value.let { it is ObdConnectionState.Idle || it is ObdConnectionState.Failed }

    override fun connect() {
        if (job?.isActive == true) return
        restart(fromDrop = false)
    }

    override fun retry() = restart(fromDrop = false)

    override fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ObdConnectionState.Idle
    }

    /** Runs [block] against the live session, or returns null when not connected. */
    suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? {
        val s = session ?: return null
        if (_state.value !is ObdConnectionState.Connected) return null
        return block(s)
    }

    private fun restart(fromDrop: Boolean) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoinQuietly()
            runLoop(startWithDelay = fromDrop)
        }
    }

    private suspend fun runLoop(startWithDelay: Boolean) {
        var retry = 0
        var lastError: ObdError = ObdError.Io("Connection dropped")
        if (startWithDelay) {
            retry = 1
            waitBeforeRetry(retry, lastError)
        }
        while (true) {
            when (val result = attempt(retry)) {
                null -> { // connected, then the session ended (heartbeat failure)
                    retry = 1
                    lastError = ObdError.Io("Connection lost")
                    waitBeforeRetry(retry, lastError)
                    continue
                }
                else -> {
                    lastError = result
                    if (result.isTerminal || retry >= policy.maxRetries) {
                        _state.value = ObdConnectionState.Failed(result, attempts = retry + 1)
                        return
                    }
                    retry++
                    waitBeforeRetry(retry, lastError)
                }
            }
        }
    }

    private suspend fun waitBeforeRetry(retry: Int, lastError: ObdError) {
        var remaining = policy.delayBefore(retry)
        while (remaining > 0) {
            _state.value = ObdConnectionState.WaitingToRetry(
                nextRetry = retry,
                maxRetries = policy.maxRetries,
                secondsRemaining = ((remaining + 999) / 1000).toInt(),
                lastError = lastError,
            )
            val step = minOf(1_000L, remaining)
            delay(step)
            remaining -= step
        }
    }

    /** One connection attempt. Returns the error, or null after a successful session ends. */
    private suspend fun attempt(retry: Int): ObdError? {
        _state.value = ObdConnectionState.Connecting(retry, policy.maxRetries)
        totalAttempts++
        var transport: ObdTransport? = null
        try {
            val t = Elm327Session.guarded { transportProvider.create() }.also { transport = it }
            Elm327Session.guarded { t.open() }
            _state.value = ObdConnectionState.Initializing(retry, policy.maxRetries)
            val s = sessionFactory(t)
            val info = Elm327Session.guarded { s.initialize() }
            session = s
            _state.value = ObdConnectionState.Connected(info)
            heartbeat(s, info)
            return null
        } catch (e: ObdError) {
            return e
        } finally {
            session = null
            transport?.let { withContext(NonCancellable) { runCatchingCancellable { it.close() } } }
        }
    }

    /** Returns normally when the link fails. */
    private suspend fun heartbeat(s: Elm327Session, info: Elm327Info) {
        var current = info
        while (true) {
            delay(heartbeatMillis)
            val voltage = try {
                Elm327Session.guarded { s.readVoltage() }
            } catch (e: ObdError) {
                return
            }
            if (voltage != null && voltage != current.voltage) {
                current = current.copy(voltage = voltage)
                _state.value = ObdConnectionState.Connected(current)
            }
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }
}
