package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.pid.PidPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What UI reads: the latest snapshot while connected, null otherwise. */
interface TelemetrySource {
    val latest: StateFlow<TelemetrySnapshot?>
}

/**
 * Runs [PidPoller] → [TelemetryEngine] in [scope] for the app's lifetime. A new connection resets the
 * engine (trip recording proper arrives in M4).
 */
class LiveTelemetry(
    scope: CoroutineScope,
    connection: ObdConnection,
    poller: PidPoller,
    private val engine: TelemetryEngine = TelemetryEngine(),
    vehicle: Flow<VehicleProfile?> = flowOf(null),
) : TelemetrySource {
    private val _latest = MutableStateFlow<TelemetrySnapshot?>(null)
    override val latest: StateFlow<TelemetrySnapshot?> = _latest.asStateFlow()

    init {
        scope.launch {
            connection.state.collect { if (it !is ObdConnectionState.Connected) { _latest.value = null; engine.reset() } }
        }
        scope.launch { vehicle.collect { engine.vehicle = it } }
        scope.launch { poller.samples.collect { _latest.value = engine.onSample(it) } }
    }
}
