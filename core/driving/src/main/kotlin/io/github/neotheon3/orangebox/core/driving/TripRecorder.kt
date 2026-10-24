package io.github.neotheon3.orangebox.core.driving

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
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
 *   overspeed and fuel-cut events;
 * - the summary is checkpointed every [CHECKPOINT_MILLIS] together with the pending rows, so process
 *   death loses at most that much; the first trip of a process closes trips a dead process left open;
 * - it ends after [ENGINE_OFF_GRACE_MILLIS] without the engine running (engine off or link lost), or on [stop].
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

    private var trip: TripEntity? = null
    private var orphansClosed = false
    private var beganAtSample = 0L
    private var base = TripTotals()
    private var last = TripTotals()
    private var lastSampleAt = Long.MIN_VALUE
    private var lastCheckpointWall = 0L
    private var engineOnWall = 0L
    private var flags = Flags()
    private val samples = mutableListOf<TripSampleEntity>()
    private val events = mutableListOf<TripEventEntity>()

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
        if (lastSampleAt == Long.MIN_VALUE || s.timestampMillis - lastSampleAt >= SAMPLE_MILLIS) {
            samples += TripSampleEntity(
                tripId = current.id, timestampMillis = s.timestampMillis,
                speedKph = s.instant.speedKph, rpm = s.instant.rpm, fuelFlowLph = s.instant.fuelFlowLph, coolantC = s.instant.coolantC,
                latitude = f?.latitude, longitude = f?.longitude, accuracyM = f?.accuracyM,
            )
            lastSampleAt = s.timestampMillis
        }
        val now = Flags(s.instant.harshAccel, s.instant.harshBrake, s.instant.overspeed, s.instant.fuelCut)
        fun edge(was: Boolean, isOn: Boolean, type: String, value: Double?) {
            if (isOn && !was) events += TripEventEntity(tripId = current.id, timestampMillis = s.timestampMillis, type = type, value = value, latitude = f?.latitude, longitude = f?.longitude)
        }
        edge(flags.harshAccel, now.harshAccel, EVENT_HARSH_ACCEL, s.instant.accelMps2)
        edge(flags.harshBrake, now.harshBrake, EVENT_HARSH_BRAKE, s.instant.accelMps2)
        edge(flags.overspeed, now.overspeed, EVENT_OVERSPEED, s.instant.speedKph)
        edge(flags.fuelCut, now.fuelCut, EVENT_FUEL_CUT, s.instant.speedKph)
        flags = now
        val updated = current.withTotals(totals).copy(lastSampleAtMillis = s.timestampMillis)
        trip = updated
        _active.value = ActiveTrip(updated.id, updated.startedAtMillis, totals)
        if (clock.now() - lastCheckpointWall >= CHECKPOINT_MILLIS) checkpoint(updated)
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
        lastSampleAt = Long.MIN_VALUE
        lastCheckpointWall = clock.now()
        samples.clear(); events.clear()
        fix = null
        locationJob = scope.launch { location.fixes().collect { fix = it } }
        _active.value = ActiveTrip(started.id, started.startedAtMillis, TripTotals())
        OrangeLog.i(OrangeLog.Areas.TRIP, "trip_start", "trip" to started.id, "vehicle" to vehicle)
    }

    private suspend fun checkGrace() {
        if (trip == null) return
        if (clock.now() - engineOnWall < ENGINE_OFF_GRACE_MILLIS) return
        finish(if (connection.state.value is ObdConnectionState.Connected) END_ENGINE_OFF else END_DISCONNECTED)
    }

    private suspend fun checkpoint(t: TripEntity) {
        trips.checkpoint(t, samples.toList(), events.toList())
        samples.clear(); events.clear()
        lastCheckpointWall = clock.now()
    }

    private suspend fun finish(reason: String) {
        val t = trip ?: return
        val closed = t.copy(endedAtMillis = maxOf(t.startedAtMillis, t.lastSampleAtMillis), endReason = reason)
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
