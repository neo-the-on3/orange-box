package io.github.neotheon3.orangebox.core.driving

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.telemetry.TelemetryConfig
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import io.github.neotheon3.orangebox.core.telemetry.TripTotals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Wall time for grace periods (samples carry their own timestamps). */
fun interface WallClock {
    fun now(): Long

    companion object {
        val SYSTEM = WallClock { System.currentTimeMillis() }
    }
}

/** The trip being recorded, for the notification and UI. */
data class ActiveTrip(val id: Long, val startedAtMillis: Long, val totals: TripTotals)

/** Where the recorder takes the active car from (an id, or null). */
fun interface ActiveVehicleId {
    fun id(): Flow<Long?>
}

/**
 * Records trips to [TripRepository] while [start]ed (by the driving service):
 * - a trip starts on engine on (RPM > 0 while connected) and zeroes the telemetry trip totals;
 * - about one sample per second (with the latest location fix while recording), plus harsh accel/brake,
 *   overspeed and fuel-cut events, each written once it ends with its peak value and duration (#164);
 * - the summary is checkpointed every [CHECKPOINT_MILLIS] together with the pending rows, so process
 *   death loses at most that much; the first trip of a process closes trips a dead process left open;
 * - it ends after [ENGINE_OFF_GRACE_MILLIS] without the engine running, or on [stop]: as engine_off only once
 *   a sample has read the engine as not running (a poll stall on a live link holds the trip open), as
 *   disconnected when the link is gone;
 * A reconnect inside the grace period continues the same trip (totals are carried over the engine reset).
 */
@Singleton
class TripRecorder(
    private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val telemetry: TelemetrySource,
    private val trips: TripRepository,
    private val location: LocationSource,
    private val clock: WallClock = WallClock.SYSTEM,
    private val vehicleId: ActiveVehicleId = ActiveVehicleId { flowOf(null) },
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        connection: ObdConnection,
        telemetry: TelemetrySource,
        trips: TripRepository,
        location: LocationSource,
        vehicles: io.github.neotheon3.orangebox.core.database.VehicleRepository,
    ) : this(scope, connection, telemetry, trips, location, WallClock.SYSTEM, ActiveVehicleId { kotlinx.coroutines.flow.flow { emit(vehicles.activeVehicle.first()?.id) } })

    private val _active = MutableStateFlow<ActiveTrip?>(null)
    val active: StateFlow<ActiveTrip?> = _active.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null
    private var locationJob: Job? = null
    @Volatile private var fix: GeoFix? = null

    /** The latest position fix of this or the last drive (the crash SMS's location), null if none yet. */
    val lastFix: GeoFix? get() = fix

    private var trip: TripEntity? = null
    private var orphansClosed = false
    private var beganAtSample = 0L
    private var base = TripTotals()
    private var last = TripTotals()
    private var lastSampleAt = Long.MIN_VALUE
    /** Sample time of the last snapshot this trip took in (stored as a sample or not). */
    private var lastSnapshotAt = Long.MIN_VALUE
    private var lastCheckpointWall = 0L
    private var engineOnWall = 0L
    /** The latest sample of this trip read the engine as not running (rpm 0 or missing). */
    private var engineOffRead = false
    private var stallLogged = false
    private var flags = Flags()
    private val samples = mutableListOf<TripSampleEntity>()
    private val events = mutableListOf<TripEventEntity>()
    /** Events in progress by type: written when they close (or the trip ends) with their peak and duration (#164). */
    private val open = linkedMapOf<String, OpenEvent>()

    private class OpenEvent(val row: TripEventEntity, var peak: Double?, var lastAt: Long)

    private data class Flags(val harshAccel: Boolean = false, val harshBrake: Boolean = false, val overspeed: Boolean = false, val fuelCut: Boolean = false)

    /** Idempotent. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                while (true) {
                    delay(TICK_MILLIS)
                    mutex.withLock { checkGrace() }
                }
            }
            telemetry.latest.collect { s -> if (s != null) mutex.withLock { onSnapshot(s) } }
        }
    }

    /** Ends the current trip (if any) with [reason] and stops recording. */
    suspend fun stop(reason: String) {
        mutex.withLock { finish(reason) }
        synchronized(this) { job?.cancel(); job = null }
    }

    private suspend fun onSnapshot(s: TelemetrySnapshot) {
        val engineOn = (s.instant.rpm ?: 0.0) > 0.0
        if (engineOn) engineOnWall = clock.now()
        engineOffRead = !engineOn
        stallLogged = false
        val current = trip
        if (current == null) {
            if (engineOn && connection.state.value is ObdConnectionState.Connected) begin(s)
            return
        }
        if (s.timestampMillis <= beganAtSample || s.timestampMillis <= lastSampleAt) return
        // The engine restarts its totals on reconnect: carry what this trip had so far.
        if (s.trip.durationS + 1e-9 < last.durationS) base += last
        last = s.trip
        val totals = base + s.trip
        val f = fix
        // A gap longer than the engine's stale limit ends its events as signal_lost at the last sample before
        // the gap, and one still over its threshold re-opens on the first sample after it. The flag never
        // drops in the snapshots either side, so close our rows the same way, or the detail would show one
        // event spanning the gap where the summary counts two (#187).
        if (lastSnapshotAt != Long.MIN_VALUE && s.timestampMillis - lastSnapshotAt > MAX_GAP_MILLIS) {
            closeAllOpen()
            flags = Flags()
        }
        lastSnapshotAt = s.timestampMillis
        if (lastSampleAt == Long.MIN_VALUE || s.timestampMillis - lastSampleAt >= SAMPLE_MILLIS) {
            samples += TripSampleEntity(
                tripId = current.id, timestampMillis = s.timestampMillis,
                speedKph = s.instant.speedKph, rpm = s.instant.rpm, fuelFlowLph = s.instant.fuelFlowLph, coolantC = s.instant.coolantC,
                latitude = f?.latitude, longitude = f?.longitude, accuracyM = f?.accuracyM,
                fuelRatePidLph = s.instant.fuelRatePidLph, mafGps = s.instant.mafGps,
            )
            lastSampleAt = s.timestampMillis
        }
        val now = Flags(s.instant.harshAccel, s.instant.harshBrake, s.instant.overspeed, s.instant.fuelCut)
        // value: the peak while the event runs: max acceleration, the strongest (most negative) braking,
        // max speed for overspeed; fuel cut keeps the speed it started at.
        // restarted: the engine counted a new event of this type while the flag stayed on (it closed the
        // previous one and opened another), so the row splits there too.
        fun edge(was: Boolean, isOn: Boolean, type: String, value: Double?, restarted: Boolean = false, peakOf: (Double, Double) -> Double) {
            if (isOn && was && restarted) open[type]?.let { close(type, it.lastAt) }
            when {
                isOn && (!was || open[type] == null) -> open[type] = OpenEvent(
                    TripEventEntity(tripId = current.id, timestampMillis = s.timestampMillis, type = type, value = value, latitude = f?.latitude, longitude = f?.longitude),
                    value, s.timestampMillis,
                )
                isOn -> open[type]?.let { o ->
                    if (value != null) o.peak = o.peak?.let { peakOf(it, value) } ?: value
                    o.lastAt = s.timestampMillis
                }
                was -> close(type, s.timestampMillis)
            }
        }
        val keep: (Double, Double) -> Double = { a, _ -> a }
        edge(flags.harshAccel, now.harshAccel, EVENT_HARSH_ACCEL, s.instant.accelMps2, totals.harshAccelCount > current.harshAccelCount, ::maxOf)
        edge(flags.harshBrake, now.harshBrake, EVENT_HARSH_BRAKE, s.instant.accelMps2, totals.harshBrakeCount > current.harshBrakeCount, ::minOf)
        edge(flags.overspeed, now.overspeed, EVENT_OVERSPEED, s.instant.speedKph, totals.overspeedCount > current.overspeedCount, ::maxOf)
        edge(flags.fuelCut, now.fuelCut, EVENT_FUEL_CUT, s.instant.speedKph, peakOf = keep)
        flags = now
        val updated = current.withTotals(totals).copy(lastSampleAtMillis = s.timestampMillis)
        trip = updated
        _active.value = ActiveTrip(updated.id, updated.startedAtMillis, totals)
        if (clock.now() - lastCheckpointWall >= CHECKPOINT_MILLIS) checkpoint(updated)
    }

    /** Moves an open event to the pending rows, ending at [endAt] (sample time). */
    private fun close(type: String, endAt: Long) {
        val o = open.remove(type) ?: return
        events += o.row.copy(value = o.peak, durationS = (endAt - o.row.timestampMillis).coerceAtLeast(0) / 1000.0)
    }

    /** Closes every open event at its last sample (a trip end, or a gap the engine ends them at). */
    private fun closeAllOpen() {
        open.keys.toList().forEach { type -> close(type, open.getValue(type).lastAt) }
    }

    private suspend fun begin(s: TelemetrySnapshot) {
        // Lazily, so merely starting the recorder never touches the database.
        if (!orphansClosed) {
            val orphans = trips.closeOrphans()
            orphansClosed = true
            if (orphans > 0) OrangeLog.i(OrangeLog.Areas.TRIP, "orphans_closed", "count" to orphans)
        }
        val vehicle = withTimeoutOrNull(1_000) { vehicleId.id().first() }
        val started = trips.start(TripEntity(vehicleId = vehicle, startedAtMillis = s.timestampMillis, lastSampleAtMillis = s.timestampMillis))
        telemetry.resetTrip()
        trip = started
        beganAtSample = s.timestampMillis
        base = TripTotals(); last = TripTotals(); flags = Flags()
        engineOffRead = false; stallLogged = false
        lastSampleAt = Long.MIN_VALUE
        lastSnapshotAt = Long.MIN_VALUE
        lastCheckpointWall = clock.now()
        samples.clear(); events.clear(); open.clear()
        fix = null
        locationJob = scope.launch { location.fixes().collect { fix = it } }
        _active.value = ActiveTrip(started.id, started.startedAtMillis, TripTotals())
        OrangeLog.i(OrangeLog.Areas.TRIP, "trip_start", "trip" to started.id, "vehicle" to vehicle)
    }

    private suspend fun checkGrace() {
        if (trip == null) return
        val silentMillis = clock.now() - engineOnWall
        if (silentMillis < ENGINE_OFF_GRACE_MILLIS) return
        val connected = connection.state.value is ObdConnectionState.Connected
        // A poll stall (no samples while the link is still up) is not engine-off: a slow or busy ELM327, or
        // a stalled device, says nothing about the engine. While connected the trip ends only once a sample
        // has actually read the engine as not running; a dead link ends it as disconnected (#142).
        if (connected && !engineOffRead) {
            if (!stallLogged) {
                stallLogged = true
                OrangeLog.i(OrangeLog.Areas.TRIP, "trip_grace_held", "reason" to "poll_stall", "silent_s" to silentMillis / 1000)
            }
            return
        }
        finish(if (connected) END_ENGINE_OFF else END_DISCONNECTED)
    }

    private suspend fun checkpoint(t: TripEntity) {
        trips.checkpoint(t, samples.toList(), events.toList())
        samples.clear(); events.clear()
        lastCheckpointWall = clock.now()
    }

    private suspend fun finish(reason: String) {
        val t = trip ?: return
        val closed = t.copy(endedAtMillis = maxOf(t.startedAtMillis, t.lastSampleAtMillis), endReason = reason)
        // Events still running end with the trip, at their last sample.
        closeAllOpen()
        checkpoint(closed)
        locationJob?.cancel(); locationJob = null
        trip = null
        _active.value = null
        OrangeLog.i(
            OrangeLog.Areas.TRIP, "trip_end", "trip" to t.id, "reason" to reason,
            "distance_km" to String.format(Locale.US, "%.2f", t.distanceKm),
            "fuel_l" to String.format(Locale.US, "%.3f", t.fuelLitres), "duration_s" to t.durationS.toLong(),
        )
    }

    companion object {
        const val SAMPLE_MILLIS = 1_000L
        const val CHECKPOINT_MILLIS = 5_000L
        const val ENGINE_OFF_GRACE_MILLIS = 30_000L
        const val TICK_MILLIS = 1_000L
        /** The telemetry engine's `maxGapMillis`: a longer gap ends its events as signal_lost. */
        val MAX_GAP_MILLIS = TelemetryConfig().maxGapMillis
        const val EVENT_HARSH_ACCEL = "harsh_accel"
        const val EVENT_HARSH_BRAKE = "harsh_brake"
        const val EVENT_OVERSPEED = "overspeed"
        const val EVENT_FUEL_CUT = "fuel_cut"
        const val END_ENGINE_OFF = "engine_off"
        const val END_DISCONNECTED = "disconnected"
        const val END_USER = "user"
    }
}

internal operator fun TripTotals.plus(o: TripTotals) = TripTotals(
    durationS = durationS + o.durationS,
    distanceKm = distanceKm + o.distanceKm,
    fuelLitres = fuelLitres + o.fuelLitres,
    co2Grams = co2Grams + o.co2Grams,
    idleS = idleS + o.idleS,
    fuelCutS = fuelCutS + o.fuelCutS,
    ecoS = ecoS + o.ecoS,
    overspeedS = overspeedS + o.overspeedS,
    harshAccelCount = harshAccelCount + o.harshAccelCount,
    harshBrakeCount = harshBrakeCount + o.harshBrakeCount,
    overspeedCount = overspeedCount + o.overspeedCount,
    speedZoneS = if (speedZoneS.isEmpty()) o.speedZoneS else if (o.speedZoneS.isEmpty()) speedZoneS
    else speedZoneS.zip(o.speedZoneS) { a, b -> a + b },
    maxSpeedKph = maxOf(maxSpeedKph, o.maxSpeedKph),
)

internal fun TripEntity.withTotals(t: TripTotals) = copy(
    durationS = t.durationS, distanceKm = t.distanceKm, fuelLitres = t.fuelLitres, co2Grams = t.co2Grams,
    idleS = t.idleS, fuelCutS = t.fuelCutS, ecoS = t.ecoS, overspeedS = t.overspeedS,
    harshAccelCount = t.harshAccelCount, harshBrakeCount = t.harshBrakeCount, overspeedCount = t.overspeedCount,
    maxSpeedKph = t.maxSpeedKph,
)
