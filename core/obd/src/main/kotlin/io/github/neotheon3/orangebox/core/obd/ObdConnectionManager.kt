package io.github.neotheon3.orangebox.core.obd

import io.github.neotheon3.orangebox.core.common.OrangeLog
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
 * While connected a heartbeat (`ATRV`) runs every [heartbeatMillis]; a heartbeat failure re-enters the
 * retry loop. A [DongleLinkEvent.Disconnected] for the selected dongle (in any active state) stops
 * everything and moves to `LinkLost`; [DongleLinkEvent.Connected] or [connect] start again.
 * The real ACL receiver and the Simulator feed the same [DongleLinkEvents], so both paths behave alike.
 */
class ObdConnectionManager(
    private val scope: CoroutineScope,
    private val transportProvider: ObdTransportProvider,
    linkEvents: Flow<DongleLinkEvent> = emptyFlow(),
    private val policy: RetryPolicy = RetryPolicy(),
    private val heartbeatMillis: Long = 5_000,
    private val sessionFactory: (ObdTransport) -> Elm327Session = { Elm327Session(it, fastPolling = true) },
    /** Settings "connect automatically when the dongle connects" (off by default in the app). */
    private val autoStartEnabled: () -> Boolean = { true },
) : ObdConnection {
    private val _state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
    override val state: StateFlow<ObdConnectionState> = _state.asStateFlow()

    /** Sets the state and logs real transitions (not the per-second countdown or voltage updates). */
    private fun setState(new: ObdConnectionState) {
        val old = _state.value
        _state.value = new
        if (old::class != new::class || (new is ObdConnectionState.WaitingToRetry && old is ObdConnectionState.WaitingToRetry && old.nextRetry != new.nextRetry)) {
            when (new) {
                is ObdConnectionState.Failed -> OrangeLog.w(AREA, "state", "to" to "Failed", "attempts" to new.attempts, "reason" to new.reason.describe())
                is ObdConnectionState.WaitingToRetry -> OrangeLog.i(
                    AREA, "retry_scheduled", "retry" to "${new.nextRetry}/${new.maxRetries}",
                    "delay_s" to new.secondsRemaining, "last_error" to new.lastError.describe(),
                )
                is ObdConnectionState.Connected -> OrangeLog.i(AREA, "state", "to" to "Connected", "protocol" to new.info.protocol.name, "voltage" to new.info.voltage)
                else -> OrangeLog.i(AREA, "state", "from" to old::class.simpleName, "to" to new::class.simpleName)
            }
        }
    }

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
                    is DongleLinkEvent.Connected -> {
                        val state = _state.value
                        val decision = AutoStartPolicy.onLinkUp(state, autoStartEnabled())
                        OrangeLog.i(AREA, "link_up", "state" to state::class.simpleName, "decision" to decision.name.lowercase())
                        if (decision != AutoStartPolicy.Decision.IGNORE) connect()
                    }
                    is DongleLinkEvent.Disconnected -> {
                        OrangeLog.i(AREA, "link_down", "state" to _state.value::class.simpleName)
                        if (_state.value !is ObdConnectionState.Idle) linkLost()
                    }
                }
            }
        }
    }

    private fun isIdleOrFailed() = _state.value.let {
        it is ObdConnectionState.Idle || it is ObdConnectionState.Failed || it is ObdConnectionState.LinkLost
    }

    private fun linkLost() {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoinQuietly()
            setState(ObdConnectionState.LinkLost)
        }
    }

    override fun connect() {
        if (job?.isActive == true) return
        if (!isIdleOrFailed()) return
        restart(fromDrop = false)
    }

    override fun retry() = restart(fromDrop = false)

    override fun disconnect() {
        job?.cancel()
        job = null
        setState(ObdConnectionState.Idle)
    }

    /** Runs [block] against the live session, or returns null when not connected. */
    override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? {
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
                        setState(ObdConnectionState.Failed(result, attempts = retry + 1))
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
            setState(ObdConnectionState.WaitingToRetry(
                nextRetry = retry,
                maxRetries = policy.maxRetries,
                secondsRemaining = ((remaining + 999) / 1000).toInt(),
                lastError = lastError,
            ))
            val step = minOf(1_000L, remaining)
            delay(step)
            remaining -= step
        }
    }

    /** One connection attempt. Returns the error, or null after a successful session ends. */
    private suspend fun attempt(retry: Int): ObdError? {
        setState(ObdConnectionState.Connecting(retry, policy.maxRetries))
        totalAttempts++
        var transport: ObdTransport? = null
        try {
            val t = Elm327Session.guarded { transportProvider.create() }.also { transport = it }
            Elm327Session.guarded { t.open() }
            setState(ObdConnectionState.Initializing(retry, policy.maxRetries))
            val s = sessionFactory(t)
            val info = Elm327Session.guarded { s.initialize() }
            session = s
            setState(ObdConnectionState.Connected(info))
            heartbeat(s, info)
            OrangeLog.w(AREA, "connection_lost", "reason" to "heartbeat failed")
            return null
        } catch (e: ObdError) {
            OrangeLog.w(AREA, "attempt_failed", "retry" to retry, "error" to e.describe())
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
                setState(ObdConnectionState.Connected(current))
            }
        }
    }

    private companion object {
        const val AREA = OrangeLog.Areas.OBD
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }
}

/**
 * What a link-up for the selected dongle does. Resuming after [ObdConnectionState.LinkLost] always
 * happens (the user had a session going); starting from Idle or Failed is the opt-in auto-start.
 */
object AutoStartPolicy {
    enum class Decision { RESUME, AUTO_START, IGNORE }

    fun onLinkUp(state: ObdConnectionState, autoStartEnabled: Boolean): Decision = when (state) {
        ObdConnectionState.LinkLost -> Decision.RESUME
        ObdConnectionState.Idle, is ObdConnectionState.Failed -> if (autoStartEnabled) Decision.AUTO_START else Decision.IGNORE
        else -> Decision.IGNORE // already connecting or connected
    }
}
