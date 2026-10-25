package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.pid.FuelType

/** Physical constants per fuel. Values and sources are listed in docs/telemetry.md. */
data class FuelProperties(
    /** Stoichiometric air-fuel ratio (mass). */
    val stoichAfr: Double,
    /** Liquid density, g/L at 15 °C. */
    val densityGramsPerLitre: Double,
    /** Tank-to-tailpipe CO₂ per litre burned, g/L. */
    val co2GramsPerLitre: Double,
) {
    companion object {
        val GASOLINE = FuelProperties(14.7, 745.0, 2310.0)
        val DIESEL = FuelProperties(14.5, 832.0, 2640.0)
        val LPG = FuelProperties(15.5, 540.0, 1640.0)
        val E85 = FuelProperties(9.8, 785.0, 1610.0)

        fun of(type: FuelType?): FuelProperties = when (type) {
            FuelType.DIESEL -> DIESEL
            FuelType.LPG -> LPG
            FuelType.ETHANOL, FuelType.METHANOL -> E85
            else -> GASOLINE
        }
    }
}

/** All thresholds the engine uses. Defaults are documented in docs/telemetry.md. */
data class TelemetryConfig(
    /** Used when the car does not report PID 51. */
    val defaultFuelType: FuelType = FuelType.GASOLINE,
    /** Engine displacement for the speed-density fallback (no MAF), litres. */
    val displacementLitres: Double = 1.6,
    /** Volumetric efficiency for the speed-density fallback at wide open throttle (MAP/baro 1.0). */
    val volumetricEfficiency: Double = 0.85,
    /** … and at idle / light load (MAP/baro ≤ [veLightLoadMapRatio]); linear in between (#194). */
    val volumetricEfficiencyLight: Double = 0.70,
    val veLightLoadMapRatio: Double = 0.25,
    /** Below this speed the car counts as stationary, km/h. */
    val stationaryKph: Double = 1.0,
    /** Instant economy is only computed above this speed, km/h. */
    val economyMinKph: Double = 5.0,
    /** Fuel cut: speed ≥ this, km/h … */
    val fuelCutMinKph: Double = 20.0,
    /** … RPM ≥ this … */
    val fuelCutMinRpm: Double = 1_000.0,
    /** … and throttle/engine load ≤ this % (throttle: until a closed position is learned). Or the ECU reports open loop / decel fuel cut. */
    val fuelCutMaxLoadPercent: Double = 8.0,
    /** … or throttle within this many % of the learned closed-throttle position (#194). */
    val fuelCutThrottleAboveClosedPercent: Double = 3.0,
    /** Idle throttle readings above this % aren't learned as "closed" (e.g. a fast idle with the pedal pressed). */
    val closedThrottleMaxPercent: Double = 25.0,
    /**
     * When the car reports the accelerator pedal (PID 49/4A): fuel cut needs the pedal within this many % of its
     * learned rest position; this takes precedence over the throttle-plate rule (RDF1 drive 2: the SX4's pedal
     * rests at 13.7 % exactly while the throttle plate sits anywhere from 14.5 to 24 % on overrun).
     */
    val fuelCutPedalAboveRestPercent: Double = 2.0,
    /** Idle pedal readings above this % aren't learned as "at rest". */
    val pedalRestMaxPercent: Double = 30.0,
    /** Eco driving: RPM ≤ this … */
    val ecoMaxRpm: Double = 2_500.0,
    /** … and |acceleration| ≤ this m/s² while moving. */
    val ecoMaxAccel: Double = 1.5,
    val overspeedKph: Double = 120.0,
    /** Harsh acceleration threshold, m/s² (≈ 0.31 g). */
    val harshAccel: Double = 3.0,
    /** Harsh braking threshold, m/s² as a positive number (≈ 0.36 g). */
    val harshBrake: Double = 3.5,
    /** Acceleration is measured over at least this much sample time, to smooth jitter and stale values, ms. */
    val accelWindowMillis: Long = 1_000,
    /** A harsh event ends when |accel| stays below this fraction of its threshold … */
    val harshReleaseFraction: Double = 0.5,
    /** … for this long (sample time), ms. */
    val eventReleaseHoldMillis: Long = 1_000,
    /** A new event of the same kind can't start sooner than this after the previous one ended, ms. */
    val eventRearmMillis: Long = 2_000,
    /** Overspeed ends only below `overspeedKph − overspeedHysteresisKph`. */
    val overspeedHysteresisKph: Double = 3.0,
    /** Gaps longer than this are not integrated (signal loss), ms. */
    val maxGapMillis: Long = 5_000,
    /** Speed zone upper bounds, km/h. The last zone is open-ended. */
    val speedZones: List<Double> = listOf(30.0, 60.0, 90.0, 120.0),
    val coolantOverheatC: Double = 110.0,
    val lowBatteryVolts: Double = 11.8,
    val overVoltageVolts: Double = 15.0,
    val lowFuelPercent: Double = 10.0,
    /** Eco level bounds on engine load, %: below the first is level 1, at/above the last is level 4. */
    val ecoLoadThresholds: List<Double> = listOf(50.0, 80.0, 90.0),
    /** Diesel eco level bounds on boost (MAP − baro), kPa. */
    val ecoBoostThresholdsKpa: List<Double> = listOf(50.0, 80.0, 100.0),
    /** Dashboard coolant lamp turns to warning at this temperature, °C (earlier than [coolantOverheatC]). */
    val coolantWarningC: Double = 100.0,
    /** Normal charging voltage with the engine running; outside it the battery lamp warns. */
    val chargingVoltsRange: ClosedFloatingPointRange<Double> = 13.2..14.8,
    /** Instant km/L scale maximum (and the value shown during fuel cut). */
    val instantKmPerLMax: Double = 30.0,
    /** RPM-panel tint escalation (#198). */
    val intensity: IntensityThresholds = IntensityThresholds(),
)

/**
 * The rpm/acceleration step that raises the legacy load/boost tint level (#198). The load/boost bands
 * themselves are [TelemetryConfig.ecoLoadThresholds] / [TelemetryConfig.ecoBoostThresholdsKpa].
 * Acceleration is the engine's windowed value; braking (negative) never raises the tint.
 */
data class IntensityThresholds(
    /** Above this rpm the panel is at least amber. */
    val amberRpm: Double = 2_500.0,
    /** Above this acceleration, m/s², the panel is at least amber. */
    val amberAccelMps2: Double = 1.5,
    /** Above this rpm the panel is red. */
    val redRpm: Double = 3_500.0,
    /** Above this acceleration, m/s², the panel is red (a harsh-acceleration event also turns it red). */
    val redAccelMps2: Double = 2.5,
) {
    companion object {
        const val AMBER = 2
        const val RED = 4
    }
}
