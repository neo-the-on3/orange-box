package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.FuelSystemStatus
import io.github.neotheon3.orangebox.core.obd.pid.FuelType
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
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
    /** Acceleration from consecutive speed samples, m/s². */
    val accelMps2: Double?,
    val fuelCut: Boolean,
    val idling: Boolean,
    val eco: Boolean,
    val overspeed: Boolean,
    val warnings: Set<Warning>,
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
 * single-threaded: feed samples in time order with [onSample].
 */
class TelemetryEngine(private val config: TelemetryConfig = TelemetryConfig()) {
    private var last: Pending? = null
    private var trip = emptyTrip()
    private var harshAccelActive = false
    private var harshBrakeActive = false

    private data class Pending(val t: Long, val speed: Double?, val flow: Double?, val instant: InstantTelemetry)

    fun reset() {
        last = null
        trip = emptyTrip()
        harshAccelActive = false
        harshBrakeActive = false
    }

    private fun emptyTrip() = TripTotals(speedZoneS = List(config.speedZones.size + 2) { 0.0 })

    fun onSample(sample: VehicleSample): TelemetrySnapshot {
        val speed = sample[ObdPid.SPEED]
        val rpm = sample[ObdPid.RPM]
        val prev = last
        val dtS = prev?.let { (sample.timestampMillis - it.t) / 1000.0 }?.takeIf { it > 0 && it * 1000 <= config.maxGapMillis }

        val accel = if (dtS != null && speed != null && prev.speed != null) (speed - prev.speed) / 3.6 / dtS else null
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
        val overspeed = speed != null && speed > config.overspeedKph
        val economy = if (speed != null && flow != null && speed >= config.economyMinKph) flow / speed * 100.0 else null

        val instant = InstantTelemetry(
            speedKph = speed, rpm = rpm,
            coolantC = sample[ObdPid.COOLANT_TEMP], fuelLevelPercent = sample[ObdPid.FUEL_LEVEL],
            voltage = sample[ObdPid.CONTROL_MODULE_VOLTAGE],
            fuelFlowLph = flow, economyLPer100Km = economy, accelMps2 = accel,
            fuelCut = fuelCut, idling = idling, eco = eco, overspeed = overspeed,
            warnings = warnings(sample),
        )

        if (dtS != null) integrate(prev, dtS, speed, flow, instant, accel)
        if (speed != null) trip = trip.copy(maxSpeedKph = maxOf(trip.maxSpeedKph, speed))
        last = Pending(sample.timestampMillis, speed, flow, instant)
        return TelemetrySnapshot(sample.timestampMillis, instant, trip)
    }

    /** Integrates the interval (prev → now) with the trapezoid rule; state flags use the previous sample. */
    private fun integrate(prev: Pending, dtS: Double, speed: Double?, flow: Double?, now: InstantTelemetry, accel: Double?) {
        val fuel = props()
        val avgSpeed = avg(prev.speed, speed)
        val avgFlow = avg(prev.flow, flow)
        val p = prev.instant
        val distance = (avgSpeed ?: 0.0) * dtS / 3600.0
        val litres = (avgFlow ?: 0.0) * dtS / 3600.0
        val zones = trip.speedZoneS.toMutableList()
        p.speedKph?.let { zones[zoneIndex(it)] += dtS }

        var ha = trip.harshAccelCount
        var hb = trip.harshBrakeCount
        if (accel != null) {
            val accelNow = accel >= config.harshAccel
            val brakeNow = -accel >= config.harshBrake
            if (accelNow && !harshAccelActive) ha++
            if (brakeNow && !harshBrakeActive) hb++
            harshAccelActive = accelNow
            harshBrakeActive = brakeNow
        }
        trip = trip.copy(
            durationS = trip.durationS + dtS,
            distanceKm = trip.distanceKm + distance,
            fuelLitres = trip.fuelLitres + litres,
            co2Grams = trip.co2Grams + litres * fuel.co2GramsPerLitre,
            idleS = trip.idleS + if (p.idling) dtS else 0.0,
            fuelCutS = trip.fuelCutS + if (p.fuelCut) dtS else 0.0,
            ecoS = trip.ecoS + if (p.eco) dtS else 0.0,
            overspeedS = trip.overspeedS + if (p.overspeed) dtS else 0.0,
            harshAccelCount = ha,
            harshBrakeCount = hb,
            speedZoneS = zones,
        )
    }

    private var reportedFuelType: FuelType? = null

    private fun props(): FuelProperties = FuelProperties.of(reportedFuelType ?: config.defaultFuelType)

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
        val displacementM3 = config.displacementLitres / 1000.0
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
