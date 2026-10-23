package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Records calls; tests drive [state] directly. */
class FakeObdConnection(initial: ObdConnectionState = ObdConnectionState.Idle) : ObdConnection {
    override val state = MutableStateFlow(initial)
    var connectCalls = 0
    var retryCalls = 0
    var disconnectCalls = 0
    override fun connect() { connectCalls++ }
    override fun retry() { retryCalls++ }
    override fun disconnect() { disconnectCalls++ }
    /** Session handed to [withSession] while [state] is Connected; null means "not connected". */
    var session: io.github.neotheon3.orangebox.core.obd.Elm327Session? = null
    override suspend fun <T> withSession(block: suspend (io.github.neotheon3.orangebox.core.obd.Elm327Session) -> T): T? =
        session?.takeIf { state.value is ObdConnectionState.Connected }?.let { block(it) }
}
