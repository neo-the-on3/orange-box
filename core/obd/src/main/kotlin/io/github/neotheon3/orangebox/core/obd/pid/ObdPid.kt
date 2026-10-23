package io.github.neotheon3.orangebox.core.obd.pid

/**
 * SAE J1979 mode 01 PIDs decoded by Orange Box. [bytes] is the number of data bytes after `41 xx`;
 * [decode] maps them (A, B, C, D…) to the value in [unit]. Formulas follow the J1979 / ISO 15031-5
 * definitions (see docs/telemetry.md).
 */
enum class ObdPid(
    val pid: Int,
    val label: String,
    val unit: String,
    val bytes: Int,
    val decode: (IntArray) -> Double,
) {
    ENGINE_LOAD(0x04, "Calculated engine load", "%", 1, { pct(it[0]) }),
    COOLANT_TEMP(0x05, "Coolant temperature", "°C", 1, { it[0] - 40.0 }),
    SHORT_FUEL_TRIM_1(0x06, "Short term fuel trim bank 1", "%", 1, { trim(it[0]) }),
    LONG_FUEL_TRIM_1(0x07, "Long term fuel trim bank 1", "%", 1, { trim(it[0]) }),
    SHORT_FUEL_TRIM_2(0x08, "Short term fuel trim bank 2", "%", 1, { trim(it[0]) }),
    LONG_FUEL_TRIM_2(0x09, "Long term fuel trim bank 2", "%", 1, { trim(it[0]) }),
    FUEL_PRESSURE(0x0A, "Fuel pressure (gauge)", "kPa", 1, { 3.0 * it[0] }),
    INTAKE_MAP(0x0B, "Intake manifold absolute pressure", "kPa", 1, { it[0].toDouble() }),
    RPM(0x0C, "Engine speed", "rpm", 2, { (256 * it[0] + it[1]) / 4.0 }),
    SPEED(0x0D, "Vehicle speed", "km/h", 1, { it[0].toDouble() }),
    TIMING_ADVANCE(0x0E, "Timing advance", "° before TDC", 1, { it[0] / 2.0 - 64.0 }),
    INTAKE_TEMP(0x0F, "Intake air temperature", "°C", 1, { it[0] - 40.0 }),
    MAF(0x10, "Mass air flow rate", "g/s", 2, { (256 * it[0] + it[1]) / 100.0 }),
    THROTTLE(0x11, "Throttle position", "%", 1, { pct(it[0]) }),
    RUN_TIME(0x1F, "Run time since engine start", "s", 2, { (256.0 * it[0] + it[1]) }),
    DISTANCE_WITH_MIL(0x21, "Distance traveled with MIL on", "km", 2, { 256.0 * it[0] + it[1] }),
    FUEL_RAIL_PRESSURE_REL(0x22, "Fuel rail pressure (relative to manifold)", "kPa", 2, { 0.079 * (256 * it[0] + it[1]) }),
    FUEL_RAIL_GAUGE_PRESSURE(0x23, "Fuel rail gauge pressure", "kPa", 2, { 10.0 * (256 * it[0] + it[1]) }),
    COMMANDED_EGR(0x2C, "Commanded EGR", "%", 1, { pct(it[0]) }),
    EGR_ERROR(0x2D, "EGR error", "%", 1, { trim(it[0]) }),
    EVAP_PURGE(0x2E, "Commanded evaporative purge", "%", 1, { pct(it[0]) }),
    FUEL_LEVEL(0x2F, "Fuel tank level input", "%", 1, { pct(it[0]) }),
    WARMUPS_SINCE_CLEAR(0x30, "Warm-ups since codes cleared", "count", 1, { it[0].toDouble() }),
    DISTANCE_SINCE_CLEAR(0x31, "Distance traveled since codes cleared", "km", 2, { 256.0 * it[0] + it[1] }),
    BAROMETRIC_PRESSURE(0x33, "Absolute barometric pressure", "kPa", 1, { it[0].toDouble() }),
    CATALYST_TEMP_B1S1(0x3C, "Catalyst temperature bank 1 sensor 1", "°C", 2, { (256 * it[0] + it[1]) / 10.0 - 40.0 }),
    CATALYST_TEMP_B2S1(0x3D, "Catalyst temperature bank 2 sensor 1", "°C", 2, { (256 * it[0] + it[1]) / 10.0 - 40.0 }),
    CONTROL_MODULE_VOLTAGE(0x42, "Control module voltage", "V", 2, { (256 * it[0] + it[1]) / 1000.0 }),
    ABSOLUTE_LOAD(0x43, "Absolute load value", "%", 2, { (256 * it[0] + it[1]) * 100.0 / 255.0 }),
    COMMANDED_EQUIVALENCE_RATIO(0x44, "Commanded air-fuel equivalence ratio", "λ", 2, { (256 * it[0] + it[1]) * 2.0 / 65536.0 }),
    RELATIVE_THROTTLE(0x45, "Relative throttle position", "%", 1, { pct(it[0]) }),
    AMBIENT_TEMP(0x46, "Ambient air temperature", "°C", 1, { it[0] - 40.0 }),
    ABSOLUTE_THROTTLE_B(0x47, "Absolute throttle position B", "%", 1, { pct(it[0]) }),
    ACCELERATOR_PEDAL_D(0x49, "Accelerator pedal position D", "%", 1, { pct(it[0]) }),
    ACCELERATOR_PEDAL_E(0x4A, "Accelerator pedal position E", "%", 1, { pct(it[0]) }),
    COMMANDED_THROTTLE(0x4C, "Commanded throttle actuator", "%", 1, { pct(it[0]) }),
    TIME_WITH_MIL(0x4D, "Time run with MIL on", "min", 2, { 256.0 * it[0] + it[1] }),
    TIME_SINCE_CLEAR(0x4E, "Time since trouble codes cleared", "min", 2, { 256.0 * it[0] + it[1] }),
    FUEL_TYPE(0x51, "Fuel type", "code", 1, { it[0].toDouble() }),
    ETHANOL_PERCENT(0x52, "Ethanol fuel %", "%", 1, { pct(it[0]) }),
    RELATIVE_PEDAL(0x5A, "Relative accelerator pedal position", "%", 1, { pct(it[0]) }),
    HYBRID_BATTERY_REMAINING(0x5B, "Hybrid battery pack remaining life", "%", 1, { pct(it[0]) }),
    OIL_TEMP(0x5C, "Engine oil temperature", "°C", 1, { it[0] - 40.0 }),
    FUEL_RATE(0x5E, "Engine fuel rate", "L/h", 2, { (256 * it[0] + it[1]) / 20.0 }),
    FUEL_SYSTEM_STATUS(0x03, "Fuel system status (bank 1)", "code", 2, { it[0].toDouble() }),
    ;

    /** Request string, e.g. `010C`. */
    val command: String get() = "01%02X".format(pid)

    fun decodeOrNull(data: IntArray?): Double? = if (data == null || data.size < bytes) null else decode(data)

    companion object {
        private val byPid = entries.associateBy { it.pid }
        fun of(pid: Int): ObdPid? = byPid[pid]
    }
}

private fun pct(a: Int) = a * 100.0 / 255.0
private fun trim(a: Int) = a * 100.0 / 128.0 - 100.0

/** PID 03 bank 1 status codes (bit values). */
object FuelSystemStatus {
    const val OPEN_LOOP_COLD = 0x01
    const val CLOSED_LOOP = 0x02
    /** Open loop due to engine load or fuel cut due to deceleration. */
    const val OPEN_LOOP_LOAD_OR_DECEL_CUT = 0x04
    const val OPEN_LOOP_FAULT = 0x08
    const val CLOSED_LOOP_FAULT = 0x10
}

/** PID 51 fuel type codes we act on (ISO 15031-5 table). */
enum class FuelType(val code: Int) {
    GASOLINE(0x01), METHANOL(0x02), ETHANOL(0x03), DIESEL(0x04), LPG(0x05), CNG(0x06), ELECTRIC(0x08),
    ;

    companion object {
        fun fromCode(code: Int?): FuelType? = entries.firstOrNull { it.code == code }
    }
}
