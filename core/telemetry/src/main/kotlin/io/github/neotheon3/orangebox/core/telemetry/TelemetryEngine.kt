package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.FuelSystemStatus
import io.github.neotheon3.orangebox.core.obd.pid.FuelType
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.common.OrangeLog
import kotlin.math.abs

enum class Warning { COOLANT_OVERHEAT, LOW_BATTERY, OVER_VOLTAGE, LOW_FUEL }

/** Instant values for the latest sample. Null means "not available" (PID missing or not meaningful). */
data class InstantTelemetry(
    val speedKph: Double?,
    val rpm: Double?,
    val coolantC: Double?,
    val fuelLevelPercent: Double?,
    val voltage: Double?,
    /** Fuel flow, L/h. 0 during fuel cut. */
    val fuelFlowLph: Double?,
    /** L/100 km; null when (nearly) stationary. */
    val economyLPer100Km: Double?,
    /** Acceleration over the last ≥ [TelemetryConfig.accelWindowMillis] of samples, m/s². */
    val accelMps2: Double?,
    val fuelCut: Boolean,
    val idling: Boolean,
    val eco: Boolean,
    val overspeed: Boolean,
    val warnings: Set<Warning>,
    /** A harsh-acceleration event is in progress (with hysteresis, see [TelemetryConfig]). */
    val harshAccel: Boolean = false,
    /** A harsh-braking event is in progress. */
    val harshBrake: Boolean = false,
)

/** Totals since [TelemetryEngine.reset]. Times in seconds. */
data class TripTotals(
    val durationS: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelLitres: Double = 0.0,
    val co2Grams: Double = 0.0,
    val idleS: Double = 0.0,
    val fuelCutS: Double = 0.0,
    val ecoS: Double = 0.0,
    val overspeedS: Double = 0.0,
    val harshAccelCount: Int = 0,
    val harshBrakeCount: Int = 0,
    val overspeedCount: Int = 0,
    /** Seconds per speed zone: [0] stationary, then one per [TelemetryConfig.speedZones] bound, then open-ended. */
    val speedZoneS: List<Double> = emptyList(),
    val maxSpeedKph: Double = 0.0,
) {
    /** Trip economy, L/100 km; null below 0.1 km. */
    val economyLPer100Km: Double? get() = if (distanceKm >= 0.1) fuelLitres / distanceKm * 100.0 else null
    val averageSpeedKph: Double? get() = if (durationS > 0) distanceKm / (durationS / 3600.0) else null
}

data class TelemetrySnapshot(val timestampMillis: Long, val instant: InstantTelemetry, val trip: TripTotals)

/**
 * Clean-room derived metrics from decoded PIDs. Formulas: docs/telemetry.md. Deterministic and
 * single-threaded. Uses sample timestamps only: duplicate or out-of-order samples (timestamp not after
 * the last accepted one) are ignored and return the previous snapshot.
 */
class TelemetryEngine(private val config: TelemetryConfig = TelemetryConfig()) {
    private var last: Pending? = null
    private var trip = emptyTrip()
    private var lastSnapshot: TelemetrySnapshot? = null
    private val speedHistory = ArrayDeque<Pair<Long, Double>>()
    private val harshAccelEvent = EventDetector(
        "harsh_accel", config.harshAccel, config.harshAccel * config.harshReleaseFraction,
        config.eventReleaseHoldMillis, config.eventRearmMillis, "m/s2",
    )
    private val harshBrakeEvent = EventDetector(
        "harsh_brake", config.harshBrake, config.harshBrake * config.harshReleaseFraction,
        config.eventReleaseHoldMillis, config.eventRearmMillis, "m/s2",
    )
    private val overspeedEvent = EventDetector(
        "overspeed", config.overspeedKph + 1e-9, config.overspeedKph - config.overspeedHysteresisKph, 0, 0, "km/h",
    )

    private data class Pending(val t: Long, val speed: Double?, val flow: Double?, val instant: InstantTelemetry)

    fun reset() {
        last = null
        lastSnapshot = null
        trip = emptyTrip()
        speedHistory.clear()
        harshAccelEvent.reset()
        harshBrakeEvent.reset()
        overspeedEvent.reset()
    }

    /**
     * Acceleration from the newest speed and the latest one at least [TelemetryConfig.accelWindowMillis]
     * older (or the oldest available if it spans ≥ half the window). Null until there is enough history.
     */
    private fun windowedAccel(t: Long, speed: Double?): Double? {
        if (speed == null) return null
        speedHistory.addLast(t to speed)
        while (speedHistory.size > 2 && speedHistory[1].first <= t - config.accelWindowMillis) speedHistory.removeFirst()
        val (t0, v0) = speedHistory.first()
        val span = t - t0
        if (span < config.accelWindowMillis / 2) return null
        return (speed - v0) / 3.6 / (span / 1000.0)
    }

    private fun emptyTrip() = TripTotals(speedZoneS = List(config.speedZones.size + 2) { 0.0 })

    fun onSample(sample: VehicleSample): TelemetrySnapshot {
        val t = sample.timestampMillis
        val prev = last
        if (prev != null && t <= prev.t) {
            OrangeLog.d(OrangeLog.Areas.TELEMETRY, "sample_ignored", "reason" to if (t == prev.t) "duplicate" else "out_of_order", "late_ms" to prev.t - t)
            return lastSnapshot!!
        }
        val speed = sample[ObdPid.SPEED]
        val rpm = sample[ObdPid.RPM]
        val gap = prev != null && t - prev.t > config.maxGapMillis
        if (gap) {
            speedHistory.clear()
            harshAccelEvent.signalLost(t); harshBrakeEvent.signalLost(t); overspeedEvent.signalLost(t)
        }
        val dtS = prev?.let { (t - it.t) / 1000.0 }?.takeIf { !gap }

        val accel = windowedAccel(t, speed)
        val newAccel = harshAccelEvent.update(t, accel)
        val newBrake = harshBrakeEvent.update(t, accel?.let { -it })
        val newOverspeed = overspeedEvent.update(t, speed)
        val load = sample[ObdPid.THROTTLE] ?: sample[ObdPid.ENGINE_LOAD]
        val status = sample[ObdPid.FUEL_SYSTEM_STATUS]?.toInt()
        // Low pedal/load when known; otherwise trust the ECU's "open loop / decel fuel cut" status.
        val lowLoad = load?.let { it <= config.fuelCutMaxLoadPercent }
        val fuelCut = speed != null && rpm != null && speed >= config.fuelCutMinKph && rpm >= config.fuelCutMinRpm &&
            (lowLoad ?: (status == FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT))
        val flow = if (fuelCut) 0.0 else fuelFlowLph(sample, rpm)
        val moving = speed != null && speed >= config.stationaryKph
        val idling = speed != null && !moving && rpm != null && rpm > 0
        val eco = moving && rpm != null && rpm <= config.ecoMaxRpm && (accel == null || abs(accel) <= config.ecoMaxAccel)
        val overspeed = overspeedEvent.active
        val economy = if (speed != null && flow != null && speed >= config.economyMinKph) flow / speed * 100.0 else null

        val instant = InstantTelemetry(
            speedKph = speed, rpm = rpm,
            coolantC = sample[ObdPid.COOLANT_TEMP], fuelLevelPercent = sample[ObdPid.FUEL_LEVEL],
            voltage = sample[ObdPid.CONTROL_MODULE_VOLTAGE],
            fuelFlowLph = flow, economyLPer100Km = economy, accelMps2 = accel,
            fuelCut = fuelCut, idling = idling, eco = eco, overspeed = overspeed,
            warnings = warnings(sample),
            harshAccel = harshAccelEvent.active,
            harshBrake = harshBrakeEvent.active,
        )

        if (dtS != null && prev != null) integrate(prev, dtS, speed, flow)
        trip = trip.copy(
            harshAccelCount = trip.harshAccelCount + if (newAccel) 1 else 0,
            harshBrakeCount = trip.harshBrakeCount + if (newBrake) 1 else 0,
            overspeedCount = trip.overspeedCount + if (newOverspeed) 1 else 0,
            maxSpeedKph = speed?.let { maxOf(trip.maxSpeedKph, it) } ?: trip.maxSpeedKph,
        )
        last = Pending(t, speed, flow, instant)
        return TelemetrySnapshot(t, instant, trip).also { lastSnapshot = it }
    }

    /** Integrates the interval (prev → now) with the trapezoid rule; state flags use the previous sample. */
    private fun integrate(prev: Pending, dtS: Double, speed: Double?, flow: Double?) {
        val fuel = props()
        val avgSpeed = avg(prev.speed, speed)
        val avgFlow = avg(prev.flow, flow)
        val p = prev.instant
        val distance = (avgSpeed ?: 0.0) * dtS / 3600.0
        val litres = (avgFlow ?: 0.0) * dtS / 3600.0
        val zones = trip.speedZoneS.toMutableList()
        p.speedKph?.let { zones[zoneIndex(it)] += dtS }

        trip = trip.copy(
            durationS = trip.durationS + dtS,
            distanceKm = trip.distanceKm + distance,
            fuelLitres = trip.fuelLitres + litres,
            co2Grams = trip.co2Grams + litres * fuel.co2GramsPerLitre,
            idleS = trip.idleS + if (p.idling) dtS else 0.0,
            fuelCutS = trip.fuelCutS + if (p.fuelCut) dtS else 0.0,
            ecoS = trip.ecoS + if (p.eco) dtS else 0.0,
            overspeedS = trip.overspeedS + if (p.overspeed) dtS else 0.0,
            speedZoneS = zones,
        )
    }

    private var reportedFuelType: FuelType? = null

    /** The user's vehicle (M3 setup). The car's own PID 51 report still wins for fuel type. */
    @Volatile var vehicle: VehicleProfile? = null

    private fun props(): FuelProperties = FuelProperties.of(reportedFuelType ?: vehicle?.fuelType ?: config.defaultFuelType)

    /**
     * Fuel flow, L/h, in order of preference: PID 5E (engine fuel rate), MAF (PID 10),
     * speed-density from MAP + IAT + RPM.
     */
    internal fun fuelFlowLph(sample: VehicleSample, rpm: Double?): Double? {
        sample[ObdPid.FUEL_TYPE]?.let { reportedFuelType = FuelType.fromCode(it.toInt()) ?: reportedFuelType }
        sample[ObdPid.FUEL_RATE]?.let { return it }
        val fuel = props()
        val maf = sample[ObdPid.MAF] ?: speedDensityMaf(sample, rpm) ?: return null
        val lambda = sample[ObdPid.COMMANDED_EQUIVALENCE_RATIO]?.takeIf { it in 0.5..2.0 } ?: 1.0
        return maf * 3600.0 / (fuel.stoichAfr * lambda * fuel.densityGramsPerLitre)
    }

    /** MAF ≈ (RPM/120) · Vd · VE · MAP · M_air / (R · T_intake), g/s. */
    internal fun speedDensityMaf(sample: VehicleSample, rpm: Double?): Double? {
        val map = sample[ObdPid.INTAKE_MAP] ?: return null
        val iat = sample[ObdPid.INTAKE_TEMP] ?: return null
        val r = rpm ?: return null
        val displacementM3 = (vehicle?.displacementLitres ?: config.displacementLitres) / 1000.0
        val airDensity = map * 1000.0 * MOLAR_MASS_AIR / (GAS_CONSTANT * (iat + 273.15)) // kg/m³
        return r / 120.0 * displacementM3 * config.volumetricEfficiency * airDensity * 1000.0
    }

    private fun warnings(s: VehicleSample): Set<Warning> = buildSet {
        s[ObdPid.COOLANT_TEMP]?.let { if (it >= config.coolantOverheatC) add(Warning.COOLANT_OVERHEAT) }
        s[ObdPid.CONTROL_MODULE_VOLTAGE]?.let {
            if (it < config.lowBatteryVolts) add(Warning.LOW_BATTERY)
            if (it > config.overVoltageVolts) add(Warning.OVER_VOLTAGE)
        }
        s[ObdPid.FUEL_LEVEL]?.let { if (it < config.lowFuelPercent) add(Warning.LOW_FUEL) }
    }

    private fun zoneIndex(speed: Double): Int {
        if (speed < config.stationaryKph) return 0
        val i = config.speedZones.indexOfFirst { speed <= it }
        return if (i < 0) config.speedZones.size + 1 else i + 1
    }

    private fun avg(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    private companion object {
        const val GAS_CONSTANT = 8.314462618 // J/(mol·K)
        const val MOLAR_MASS_AIR = 0.0289647 // kg/mol
    }
}

/** What the engine needs to know about the car when the ECU doesn't say. */
data class VehicleProfile(val fuelType: FuelType?, val displacementLitres: Double?)
