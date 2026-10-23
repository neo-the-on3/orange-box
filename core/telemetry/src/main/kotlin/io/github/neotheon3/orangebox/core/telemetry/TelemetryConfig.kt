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
    /** Volumetric efficiency for the speed-density fallback. */
    val volumetricEfficiency: Double = 0.85,
    /** Below this speed the car counts as stationary, km/h. */
    val stationaryKph: Double = 1.0,
    /** Instant economy is only computed above this speed, km/h. */
    val economyMinKph: Double = 5.0,
    /** Fuel cut: speed ≥ this, km/h … */
    val fuelCutMinKph: Double = 20.0,
    /** … RPM ≥ this … */
    val fuelCutMinRpm: Double = 1_000.0,
    /** … and throttle/engine load ≤ this %. Or the ECU reports open loop / decel fuel cut. */
    val fuelCutMaxLoadPercent: Double = 8.0,
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
)
