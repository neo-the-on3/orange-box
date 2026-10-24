package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.MonitorStatus
import io.github.neotheon3.orangebox.core.obd.ObdError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * One poll result. [values] holds the latest decoded value of every PID read so far in this session;
 * [monitor] the latest MIL state and DTC count (PID 01 01), read on slow ticks.
 */
data class VehicleSample(
    val timestampMillis: Long,
    val values: Map<ObdPid, Double>,
    val monitor: MonitorStatus? = null,
) {
    operator fun get(pid: ObdPid): Double? = values[pid]
}

data class PollingConfig(
    /** Interval between fast polls (RPM, speed, MAF, load…). */
    val fastIntervalMillis: Long = 500,
    /** Slow PIDs (coolant, fuel level, voltage…) are read every [slowEvery] fast ticks. */
    val slowEvery: Int = 10,
    val fast: List<ObdPid> = listOf(
        ObdPid.RPM, ObdPid.SPEED, ObdPid.MAF, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE, ObdPid.FUEL_RATE,
        ObdPid.ACCELERATOR_PEDAL_D,
    ),
    val slow: List<ObdPid> = listOf(
        ObdPid.COOLANT_TEMP, ObdPid.FUEL_LEVEL, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.INTAKE_TEMP,
        ObdPid.INTAKE_MAP, ObdPid.FUEL_SYSTEM_STATUS, ObdPid.AMBIENT_TEMP, ObdPid.FUEL_TYPE, ObdPid.OIL_TEMP,
        // Detailed Info grid (dashboard).
        ObdPid.BAROMETRIC_PRESSURE, ObdPid.SHORT_FUEL_TRIM_1, ObdPid.LONG_FUEL_TRIM_1, ObdPid.FUEL_PRESSURE,
        ObdPid.DISTANCE_WITH_MIL, ObdPid.O2_B1S1_VOLTAGE,
    ),
    /** Read MIL state + DTC count (PID 01 01) on slow ticks when supported. */
    val monitorStatus: Boolean = true,
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
        io.github.neotheon3.orangebox.core.common.OrangeLog.i(
            io.github.neotheon3.orangebox.core.common.OrangeLog.Areas.TELEMETRY, "polling_started",
            "supported" to supported.all.size, "fast" to fast.size, "slow" to slow.size,
        )
        val values = LinkedHashMap<ObdPid, Double>()
        val readMonitor = config.monitorStatus && MONITOR_STATUS_PID in supported
        var monitor: MonitorStatus? = null
        var tick = 0L
        while (true) {
            val slowTick = tick % config.slowEvery == 0L
            val toRead = if (slowTick) fast + slow else fast
            // Timestamp = when SPEED was requested (else the start of the tick), not when the whole
            // tick finished: under load a tick can take much longer, which skewed acceleration.
            var sampledAt = clock()
            val ok = try {
                connection.withSession { s ->
                    toRead.forEach { pid ->
                        if (pid == ObdPid.SPEED) sampledAt = clock()
                        s.read(pid)?.let { values[pid] = it }
                    }
                    if (slowTick && readMonitor) s.readMonitorStatus()?.let { monitor = it }
                } != null
            } catch (e: ObdError) {
                false // link failure: the connection manager notices and leaves Connected
            }
            if (!ok) return@flow
            emit(VehicleSample(sampledAt, values.toMap(), monitor))
            tick++
            delay(config.fastIntervalMillis)
        }
    }

    private companion object {
        const val MONITOR_STATUS_PID = 0x01
    }
}
