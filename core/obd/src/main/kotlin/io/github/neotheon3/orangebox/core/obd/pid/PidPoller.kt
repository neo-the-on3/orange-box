package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.MonitorStatus
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.common.OrangeLog
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
    /**
     * Target period of one fast tick, measured from the start of the tick (#193): the poller sleeps only for
     * what is left of it, so a slow dongle runs ticks back to back instead of adding a fixed pause.
     */
    val fastIntervalMillis: Long = 500,
    /** Each slow PID is read once every [slowEvery] ticks; slow PIDs are spread over the ticks (#193). */
    val slowEvery: Int = 10,
    /**
     * Read first on every tick, and the sample is published right after them, so the dashboard's speed and
     * rpm are one request old instead of a whole tick (#193). The rest of the tick refreshes the other
     * values for the next sample.
     */
    val priority: List<ObdPid> = listOf(ObdPid.SPEED, ObdPid.RPM),
    val fast: List<ObdPid> = listOf(
        ObdPid.RPM, ObdPid.SPEED, ObdPid.MAF, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE, ObdPid.FUEL_RATE,
        ObdPid.ACCELERATOR_PEDAL_D,
        // Fuel cut is confirmed by the ECU's "open loop / decel fuel cut" status (#194); one byte, so cheap.
        ObdPid.FUEL_SYSTEM_STATUS,
    ),
    val slow: List<ObdPid> = listOf(
        ObdPid.COOLANT_TEMP, ObdPid.FUEL_LEVEL, ObdPid.CONTROL_MODULE_VOLTAGE, ObdPid.INTAKE_TEMP,
        ObdPid.INTAKE_MAP, ObdPid.AMBIENT_TEMP, ObdPid.FUEL_TYPE, ObdPid.OIL_TEMP,
        // Detailed Info grid (dashboard).
        ObdPid.BAROMETRIC_PRESSURE, ObdPid.SHORT_FUEL_TRIM_1, ObdPid.LONG_FUEL_TRIM_1, ObdPid.FUEL_PRESSURE,
        ObdPid.DISTANCE_WITH_MIL, ObdPid.O2_B1S1_VOLTAGE,
    ),
    /** Read MIL state + DTC count (PID 01 01) every [slowEvery] ticks when supported. */
    val monitorStatus: Boolean = true,
    /** How often `polling_stats` is logged (connection log), ms of sample time. 0 = never. */
    val statsIntervalMillis: Long = 30_000,
)

/**
 * Polls mode 01 PIDs while connected and emits one [VehicleSample] per fast tick.
 * Only PIDs announced in the vehicle's supported-PID bitmaps are ever requested. Polling stops when
 * the connection leaves Connected and restarts (with a fresh bitmap read) on the next Connected.
 *
 * Tick order (#193): the [PollingConfig.priority] PIDs (speed, rpm) first, then the sample is emitted,
 * then the other fast PIDs and this tick's share of the slow PIDs, then a sleep for the rest of the period.
 * The very first tick reads everything before its sample so no value starts out missing.
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

    /** Slow PIDs (and the monitor read) due on [tick]: slow PID `i` on ticks where `tick % slowEvery == i % slowEvery`. */
    internal fun slowDue(slow: List<ObdPid>, tick: Long): List<ObdPid> {
        val phase = (tick % config.slowEvery).toInt()
        return slow.filterIndexed { i, _ -> i % config.slowEvery == phase }
    }

    private fun pollSession(): Flow<VehicleSample> = flow {
        val supported = connection.withSession { it.readSupportedPids() } ?: return@flow
        val fast = supported.filter(config.fast)
        val slow = supported.filter(config.slow)
        val priority = supported.filter(config.priority)
        val rest = fast.filter { it !in priority }
        activePids = (fast + slow + priority).toSet()
        OrangeLog.i(
            OrangeLog.Areas.TELEMETRY, "polling_started",
            "supported" to supported.all.size, "fast" to fast.size, "slow" to slow.size,
            "period_ms" to config.fastIntervalMillis,
        )
        val values = LinkedHashMap<ObdPid, Double>()
        val readMonitor = config.monitorStatus && MONITOR_STATUS_PID in supported
        var monitor: MonitorStatus? = null
        val stats = PollingStats(clock())
        var tick = 0L
        while (true) {
            val tickStart = clock()
            val slowNow = slowDue(slow, tick)
            val monitorNow = readMonitor && tick % config.slowEvery == 0L
            var sampledAt = tickStart
            suspend fun Elm327Session.timedRead(pid: ObdPid) {
                val t0 = clock()
                if (pid == ObdPid.SPEED) sampledAt = t0
                read(pid)?.let { values[pid] = it }
                stats.request(clock() - t0)
            }
            val ok = try {
                connection.withSession { s ->
                    priority.forEach { s.timedRead(it) }
                    if (tick == 0L) {
                        (rest + slowNow).forEach { s.timedRead(it) }
                        if (monitorNow) s.readMonitorStatus()?.let { monitor = it }
                        emit(VehicleSample(sampledAt, values.toMap(), monitor))
                    } else {
                        emit(VehicleSample(sampledAt, values.toMap(), monitor))
                        (rest + slowNow).forEach { s.timedRead(it) }
                        if (monitorNow) s.readMonitorStatus()?.let { monitor = it }
                    }
                } != null
            } catch (e: ObdError) {
                false // link failure: the connection manager notices and leaves Connected
            }
            if (!ok) return@flow
            val elapsed = clock() - tickStart
            stats.tick(sampledAt, elapsed)
            if (config.statsIntervalMillis > 0 && clock() - stats.since >= config.statsIntervalMillis) {
                stats.log(clock())
                stats.reset(clock())
            }
            tick++
            delay((config.fastIntervalMillis - elapsed).coerceAtLeast(0))
        }
    }

    private companion object {
        const val MONITOR_STATUS_PID = 0x01
    }
}

/** Rolling polling health for the connection log (#193): tick rate, sample spacing and request latency. */
internal class PollingStats(var since: Long) {
    private var ticks = 0
    private var maxTickMs = 0L
    private var tickMsSum = 0L
    private var lastSample: Long? = null
    private val sampleGaps = ArrayList<Long>()
    private val requestMs = ArrayList<Long>()

    fun request(ms: Long) { requestMs += ms }

    fun tick(sampledAt: Long, tickMs: Long) {
        ticks++
        tickMsSum += tickMs
        maxTickMs = maxOf(maxTickMs, tickMs)
        lastSample?.let { sampleGaps += sampledAt - it }
        lastSample = sampledAt
    }

    fun log(now: Long) {
        val secs = (now - since).coerceAtLeast(1) / 1000.0
        OrangeLog.i(
            OrangeLog.Areas.TELEMETRY, "polling_stats",
            "ticks_per_s" to "%.2f".format(java.util.Locale.US, ticks / secs),
            "tick_ms_avg" to if (ticks > 0) tickMsSum / ticks else 0, "tick_ms_max" to maxTickMs,
            "sample_gap_ms_p50" to percentile(sampleGaps, 50), "sample_gap_ms_p95" to percentile(sampleGaps, 95),
            "sample_gap_ms_max" to (sampleGaps.maxOrNull() ?: 0),
            "requests" to requestMs.size, "request_ms_p50" to percentile(requestMs, 50), "request_ms_p95" to percentile(requestMs, 95),
        )
    }

    fun reset(now: Long) {
        since = now; ticks = 0; maxTickMs = 0; tickMsSum = 0
        sampleGaps.clear(); requestMs.clear()
    }

    /** Snapshot for tests. */
    fun sampleGaps(): List<Long> = sampleGaps.toList()

    companion object {
        fun percentile(values: List<Long>, p: Int): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            return sorted[((p / 100.0) * (sorted.size - 1)).toInt()]
        }
    }
}
