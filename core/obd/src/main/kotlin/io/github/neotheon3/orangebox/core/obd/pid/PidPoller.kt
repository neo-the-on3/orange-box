package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** One poll result. [values] holds the latest decoded value of every PID read so far in this session. */
data class VehicleSample(
    val timestampMillis: Long,
    val values: Map<ObdPid, Double>,
) {
    operator fun get(pid: ObdPid): Double? = values[pid]
}

data class PollingConfig(
    /** Interval between fast polls (RPM, speed, MAF, load…). */
    val fastIntervalMillis: Long = 500,
    /** Slow PIDs (coolant, fuel level, voltage…) are read every [slowEvery] fast ticks. */
    val slowEvery: Int = 10,
    val fast: List<ObdPid> = listOf(ObdPid.RPM, ObdPid.SPEED, ObdPid.MAF, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE, ObdPid.FUEL_RATE),
    val slow: List<ObdPid> = listOf(
        ObdPid.COOLANT_TEMP, ObdPid.FUEL_LEVEL, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.INTAKE_TEMP,
        ObdPid.INTAKE_MAP, ObdPid.FUEL_SYSTEM_STATUS, ObdPid.AMBIENT_TEMP, ObdPid.FUEL_TYPE, ObdPid.OIL_TEMP,
    ),
)

/**
 * Polls mode 01 PIDs while connected and emits a [VehicleSample] after every fast tick.
 * Only PIDs announced in the vehicle's supported-PID bitmaps are ever requested. Polling stops when
 * the connection leaves Connected and restarts (with a fresh bitmap read) on the next Connected.
 */
class PidPoller(
    private val connection: ObdConnection,
    private val config: PollingConfig = PollingConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val samples: Flow<VehicleSample> = connection.state
        .distinctUntilChangedBy { it is ObdConnectionState.Connected }
        .flatMapLatest { if (it is ObdConnectionState.Connected) pollSession() else emptyFlow() }

    /** PIDs actually polled in the current session (for diagnostics/tests). */
    @Volatile var activePids: Set<ObdPid> = emptySet()
        private set

    private fun pollSession(): Flow<VehicleSample> = flow {
        val supported = connection.withSession { it.readSupportedPids() } ?: return@flow
        val fast = supported.filter(config.fast)
        val slow = supported.filter(config.slow)
        activePids = (fast + slow).toSet()
        val values = LinkedHashMap<ObdPid, Double>()
        var tick = 0L
        while (true) {
            val toRead = if (tick % config.slowEvery == 0L) fast + slow else fast
            val ok = try {
                connection.withSession { s -> toRead.forEach { pid -> s.read(pid)?.let { values[pid] = it } } } != null
            } catch (e: ObdError) {
                false // link failure: the connection manager notices and leaves Connected
            }
            if (!ok) return@flow
            emit(VehicleSample(clock(), values.toMap()))
            tick++
            delay(config.fastIntervalMillis)
        }
    }
}
