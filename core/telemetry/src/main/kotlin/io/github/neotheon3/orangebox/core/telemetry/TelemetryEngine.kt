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
    /**
     * Engine effort while moving, for the eco lamp and cluster tint: 0 = not shown (stopped or no data),
     * 1 = light, 2–3 = moderate, 4 = heavy. See [TelemetryConfig.ecoLoadThresholds].
     */
    val ecoLevel: Int = 0,
    /**
     * Driving intensity for the RPM-panel tint (#198): 0 = no tint (stopped or no data), 1 = green,
     * 2–3 = amber, 4 = red. The legacy load/boost level ([ecoLevel]) raised by the rpm/acceleration step
     * in [TelemetryConfig.intensity]. Unlike the ECO lamp it is not gated on the eco rule.
     */
    val intensityLevel: Int = 0,
    /** Coolant at or above [TelemetryConfig.coolantWarningC]. */
    val coolantWarning: Boolean = false,
    /** Engine running and voltage outside [TelemetryConfig.chargingVoltsRange]. */
    val batteryWarning: Boolean = false,
    /** Instant economy, km/L, capped at [TelemetryConfig.instantKmPerLMax] (full during fuel cut). */
    val instantKmPerL: Double? = null,
    /** MIL lamp state from PID 01 01, null if unknown. */
    val milOn: Boolean? = null,
    /** Stored DTC count from PID 01 01, null if unknown. */
    val dtcCount: Int? = null,
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
    /** Trip economy, km/L; null below 0.1 km or with no fuel burned yet. */
    val economyKmPerL: Double? get() = if (distanceKm >= 0.1 && fuelLitres > 0) distanceKm / fuelLitres else null
}

/** [values] are the raw decoded PIDs of the sample, for detail views. */
data class TelemetrySnapshot(
    val timestampMillis: Long,
    val instant: InstantTelemetry,
    val trip: TripTotals,
    val values: Map<ObdPid, Double> = emptyMap(),
)

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
        endValueKey = "speed_kmh",
    )

    private data class Pending(val t: Long, val speed: Double?, val flow: Double?, val instant: InstantTelemetry)

    /** Starts new [TripTotals] (a new recorded trip) and keeps the instant state and event detectors. */
    fun resetTrip() {
        trip = emptyTrip()
    }

    fun reset() {
        closedThrottle = null
        lastFuelSource = null
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
        if (prev != null && gap) {
            speedHistory.clear()
            // Close at the last sample before the gap: the gap itself is not integrated into the trip totals.
            harshAccelEvent.signalLost(prev.t); harshBrakeEvent.signalLost(prev.t); overspeedEvent.signalLost(prev.t)
        }
        val dtS = prev?.let { (t - it.t) / 1000.0 }?.takeIf { !gap }

        val accel = windowedAccel(t, speed)
        val newAccel = harshAccelEvent.update(t, accel)
        val newBrake = harshBrakeEvent.update(t, accel?.let { -it })
        applyOverspeedThreshold()
        val newOverspeed = overspeedEvent.update(t, speed)
        val moving = speed != null && speed >= config.stationaryKph
        val idling = speed != null && !moving && rpm != null && rpm > 0
        val fuelCut = fuelCut(sample, speed, rpm, idling)
        val flow = if (fuelCut) 0.0 else fuelFlowLph(sample, rpm)
        val eco = moving && rpm != null && rpm <= config.ecoMaxRpm && (accel == null || abs(accel) <= config.ecoMaxAccel)
        val overspeed = overspeedEvent.active
        val economy = if (speed != null && flow != null && speed >= config.economyMinKph) flow / speed * 100.0 else null
        val kmPerL = when {
            fuelCut -> config.instantKmPerLMax
            speed == null || flow == null -> null
            speed < config.economyMinKph -> 0.0
            flow <= 0.0 -> config.instantKmPerLMax
            else -> (speed / flow).coerceAtMost(config.instantKmPerLMax)
        }
        val coolant = sample[ObdPid.COOLANT_TEMP]
        val volts = sample[ObdPid.CONTROL_MODULE_VOLTAGE]
        val engineOn = rpm != null && rpm > 0

        val loadLevel = if (moving) ecoLevel(sample) else 0

        val instant = InstantTelemetry(
            speedKph = speed, rpm = rpm,
            coolantC = coolant, fuelLevelPercent = sample[ObdPid.FUEL_LEVEL],
            voltage = volts,
            fuelFlowLph = flow, economyLPer100Km = economy, accelMps2 = accel,
            fuelCut = fuelCut, idling = idling, eco = eco, overspeed = overspeed,
            warnings = warnings(sample),
            harshAccel = harshAccelEvent.active,
            harshBrake = harshBrakeEvent.active,
            ecoLevel = loadLevel,
            intensityLevel = if (moving) intensityLevel(loadLevel, rpm, accel, harshAccelEvent.active) else 0,
            coolantWarning = coolant != null && coolant >= config.coolantWarningC,
            batteryWarning = engineOn && volts != null && volts !in config.chargingVoltsRange,
            instantKmPerL = kmPerL,
            milOn = sample.monitor?.milOn,
            dtcCount = sample.monitor?.dtcCount,
        )

        if (dtS != null && prev != null) integrate(prev, dtS, speed, flow)
        trip = trip.copy(
            harshAccelCount = trip.harshAccelCount + if (newAccel) 1 else 0,
            harshBrakeCount = trip.harshBrakeCount + if (newBrake) 1 else 0,
            overspeedCount = trip.overspeedCount + if (newOverspeed) 1 else 0,
            maxSpeedKph = speed?.let { maxOf(trip.maxSpeedKph, it) } ?: trip.maxSpeedKph,
        )
        last = Pending(t, speed, flow, instant)
        return TelemetrySnapshot(t, instant, trip, sample.values).also { lastSnapshot = it }
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

    /**
     * Petrol/LPG: engine load (PID 04) against [TelemetryConfig.ecoLoadThresholds]. Diesel: boost
     * (MAP − baro, kPa) against [TelemetryConfig.ecoBoostThresholdsKpa], falling back to load.
     */
    private fun ecoLevel(sample: VehicleSample): Int {
        val diesel = (reportedFuelType ?: vehicle?.fuelType ?: config.defaultFuelType) == FuelType.DIESEL
        val map = sample[ObdPid.INTAKE_MAP]
        val (value, bounds) = if (diesel && map != null) {
            (map - (sample[ObdPid.BAROMETRIC_PRESSURE] ?: STANDARD_BARO_KPA)) to config.ecoBoostThresholdsKpa
        } else {
            (sample[ObdPid.ENGINE_LOAD] ?: return 0) to config.ecoLoadThresholds
        }
        return 1 + bounds.count { value >= it }
    }

    /**
     * Closed-throttle position (PID 11), learned per connection as the lowest reading while idling (#194).
     * Absolute throttle often reads 12–20 % with the pedal released (16.1 % on Kiran's SX4), so a fixed
     * "≤ 8 %" never saw a fuel cut on such cars.
     */
    private var closedThrottle: Double? = null

    /** The learned closed-throttle position, % (null until the engine has idled with throttle data). */
    val closedThrottlePercent: Double? get() = closedThrottle

    /**
     * Decel fuel cut (#194): moving ≥ [TelemetryConfig.fuelCutMinKph] at ≥ [TelemetryConfig.fuelCutMinRpm] and
     * - throttle (PID 11) within [TelemetryConfig.fuelCutThrottleAboveClosedPercent] of the learned closed
     *   position (before one is learned: ≤ [TelemetryConfig.fuelCutMaxLoadPercent]), unless the ECU's fuel
     *   system status (PID 03) says closed loop (it is still injecting);
     * - without throttle: engine load ≤ [TelemetryConfig.fuelCutMaxLoadPercent], else the ECU's
     *   "open loop / decel fuel cut" status.
     */
    private fun fuelCut(sample: VehicleSample, speed: Double?, rpm: Double?, idling: Boolean): Boolean {
        val throttle = sample[ObdPid.THROTTLE]
        if (throttle != null && idling && throttle <= config.closedThrottleMaxPercent) {
            closedThrottle = closedThrottle?.let { minOf(it, throttle) } ?: throttle
        }
        if (speed == null || rpm == null || speed < config.fuelCutMinKph || rpm < config.fuelCutMinRpm) return false
        val status = sample[ObdPid.FUEL_SYSTEM_STATUS]?.toInt()
        if (throttle != null) {
            val closed = closedThrottle?.let { throttle - it <= config.fuelCutThrottleAboveClosedPercent }
                ?: (throttle <= config.fuelCutMaxLoadPercent)
            return closed && status != FuelSystemStatus.CLOSED_LOOP
        }
        val load = sample[ObdPid.ENGINE_LOAD]
        return load?.let { it <= config.fuelCutMaxLoadPercent } ?: (status == FuelSystemStatus.OPEN_LOOP_LOAD_OR_DECEL_CUT)
    }

    /** Legacy load/boost level raised to amber/red by rpm or acceleration ([TelemetryConfig.intensity]). */
    private fun intensityLevel(loadLevel: Int, rpm: Double?, accel: Double?, harshAccel: Boolean): Int {
        val t = config.intensity
        val step = when {
            harshAccel || (rpm != null && rpm > t.redRpm) || (accel != null && accel > t.redAccelMps2) -> IntensityThresholds.RED
            (rpm != null && rpm > t.amberRpm) || (accel != null && accel > t.amberAccelMps2) -> IntensityThresholds.AMBER
            else -> 0
        }
        return maxOf(loadLevel, step)
    }

    private var reportedFuelType: FuelType? = null

    /**
     * Overspeed threshold, km/h (Settings; [TelemetryConfig.overspeedKph] by default). Overspeed starts above it
     * and ends below it − [TelemetryConfig.overspeedHysteresisKph]. Applied from the next sample.
     */
    @Volatile var overspeedKph: Double = config.overspeedKph

    private fun applyOverspeedThreshold() {
        val kph = overspeedKph
        if (overspeedEvent.startAt != kph + 1e-9) overspeedEvent.setThresholds(kph + 1e-9, kph - config.overspeedHysteresisKph)
    }

    /** The user's vehicle (M3 setup). The car's own PID 51 report still wins for fuel type. */
    @Volatile var vehicle: VehicleProfile? = null

    private fun props(): FuelProperties = FuelProperties.of(reportedFuelType ?: vehicle?.fuelType ?: config.defaultFuelType)

    /**
     * Fuel flow, L/h, in order of preference: PID 5E (engine fuel rate), MAF (PID 10),
     * speed-density from MAP + IAT + RPM.
     */
    internal fun fuelFlowLph(sample: VehicleSample, rpm: Double?): Double? {
        sample[ObdPid.FUEL_TYPE]?.let { reportedFuelType = FuelType.fromCode(it.toInt()) ?: reportedFuelType }
        sample[ObdPid.FUEL_RATE]?.let { noteFuelSource("fuel_rate"); return it }
        val fuel = props()
        val maf = sample[ObdPid.MAF]?.also { noteFuelSource("maf") }
            ?: speedDensityMaf(sample, rpm)?.also { noteFuelSource("speed_density") } ?: return null
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
        val baro = sample[ObdPid.BAROMETRIC_PRESSURE] ?: STANDARD_BARO_KPA
        return r / 120.0 * displacementM3 * volumetricEfficiency(map / baro) * airDensity * 1000.0
    }

    /**
     * Volumetric efficiency for speed-density (#194): [TelemetryConfig.volumetricEfficiencyLight] at or below
     * [TelemetryConfig.veLightLoadMapRatio] (idle / light load, MAP/baro), rising linearly to
     * [TelemetryConfig.volumetricEfficiency] at wide open throttle (MAP/baro 1.0).
     */
    internal fun volumetricEfficiency(mapRatio: Double): Double {
        val light = config.volumetricEfficiencyLight
        val full = config.volumetricEfficiency
        val lo = config.veLightLoadMapRatio
        val f = ((mapRatio - lo) / (1.0 - lo)).coerceIn(0.0, 1.0)
        return light + (full - light) * f
    }

    /** Fuel-flow source in use, logged when it changes so feedback can be triaged from the connection log. */
    @Volatile var fuelSource: String? = null
        private set
    private var lastFuelSource: String? = null

    private fun noteFuelSource(source: String) {
        fuelSource = source
        if (source != lastFuelSource) {
            lastFuelSource = source
            OrangeLog.i(
                OrangeLog.Areas.TELEMETRY, "fuel_source", "source" to source, "fuel" to (reportedFuelType ?: vehicle?.fuelType ?: config.defaultFuelType).name,
                "displacement_l" to (vehicle?.displacementLitres ?: config.displacementLitres),
            )
        }
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
        const val STANDARD_BARO_KPA = 101.3
    }
}

/** What the engine needs to know about the car when the ECU doesn't say. */
data class VehicleProfile(val fuelType: FuelType?, val displacementLitres: Double?)
