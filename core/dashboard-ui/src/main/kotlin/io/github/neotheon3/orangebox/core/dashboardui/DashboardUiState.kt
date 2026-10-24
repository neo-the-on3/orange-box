package io.github.neotheon3.orangebox.core.dashboardui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import java.util.Locale
import kotlin.math.roundToInt

/** Coolant / battery lamp: off without data, blue when normal, orange on warning. */
enum class IndicatorLevel { OFF, NORMAL, WARNING }

enum class DetailIcon { BATTERY, COOLANT, MIL, PIN, OIL, OBD, ECO, OVERSPEED, IDLING }

@Immutable
data class DetailTileState(
    @StringRes val title: Int,
    val value: String,
    val unit: String,
    val status: TileStatus,
    val large: Boolean = false,
    val icon: DetailIcon? = null,
)

/**
 * Everything the dashboard, the Detailed Info grid and the Home mini-dashboard show, already formatted.
 * The default value is the "engine off / no data" state: placeholders, lamps off, panels idle.
 */
@Immutable
data class DashboardUiState(
    /** Connected and samples are flowing. */
    val live: Boolean = false,
    val rpm: String = NO_INT,
    val speed: String = NO_INT,
    val fuelCut: Boolean = false,
    val overspeed: Boolean = false,
    val harshAccel: Boolean = false,
    val harshBrake: Boolean = false,
    val idling: Boolean = false,
    /** 0 off, 1 green, 2–3 yellow, 4 red. */
    val ecoLevel: Int = 0,
    val coolant: IndicatorLevel = IndicatorLevel.OFF,
    val battery: IndicatorLevel = IndicatorLevel.OFF,
    val milOn: Boolean = false,
    val dtcCount: Int? = null,
    val obdOn: Boolean = false,
    val tripKmPerL: String = NO_DECIMAL,
    val instantKmPerL: String = NO_DECIMAL,
    /** Instant economy bar fill, 0..1 of the 0–30 km/L scale. */
    val instantFraction: Float = 0f,
    val fuelUsedL: String = NO_DECIMAL,
    val tripKm: String = NO_DECIMAL,
    val tripTime: String = NO_TIME,
    val leftGlow: PanelGlow = PanelGlow.NONE,
    val rightGlow: PanelGlow = PanelGlow.NONE,
    val detailPages: List<List<DetailTileState>> = DashboardStateMapper.detailPages(null, emptySet()),
) {
    companion object {
        const val NO_INT = "---"
        const val NO_DECIMAL = "--.-"
        const val NO_TIME = "-:--:--"
    }
}

/** Maps connection + telemetry to [DashboardUiState] (rules: docs/ui/LEGACY_OBD_UI.md §4.3, docs/telemetry.md). */
object DashboardStateMapper {
    private const val KM_PER_L_SCALE = 30.0
    private const val TRIP_KM_PER_L_CAP = 99.9

    fun map(
        connection: ObdConnectionState,
        snapshot: TelemetrySnapshot?,
        activePids: Set<ObdPid> = emptySet(),
    ): DashboardUiState {
        if (connection !is ObdConnectionState.Connected || snapshot == null) {
            return DashboardUiState(obdOn = connection is ObdConnectionState.Connected)
        }
        val i = snapshot.instant
        val trip = snapshot.trip
        return DashboardUiState(
            live = true,
            rpm = i.rpm?.takeIf { it >= 0 }?.roundToInt()?.toString() ?: DashboardUiState.NO_INT,
            speed = i.speedKph?.takeIf { it >= 0 }?.roundToInt()?.toString() ?: DashboardUiState.NO_INT,
            fuelCut = i.fuelCut,
            overspeed = i.overspeed,
            harshAccel = i.harshAccel,
            harshBrake = i.harshBrake,
            idling = i.idling,
            ecoLevel = i.ecoLevel,
            coolant = level(i.coolantC != null, i.coolantWarning),
            battery = level(i.voltage != null, i.batteryWarning),
            milOn = i.milOn == true,
            dtcCount = i.dtcCount,
            obdOn = true,
            tripKmPerL = decimal(trip.economyKmPerL?.coerceAtMost(TRIP_KM_PER_L_CAP)),
            instantKmPerL = decimal(i.instantKmPerL),
            instantFraction = ((i.instantKmPerL ?: 0.0) / KM_PER_L_SCALE).toFloat().coerceIn(0f, 1f),
            fuelUsedL = decimal(trip.fuelLitres),
            tripKm = decimal(trip.distanceKm),
            tripTime = elapsed(trip.durationS),
            leftGlow = leftGlow(i.fuelCut, i.ecoLevel),
            rightGlow = rightGlow(i.overspeed, i.harshAccel || i.harshBrake),
            detailPages = detailPages(snapshot, activePids),
        )
    }

    /** RPM panel: fuel cut (green, FUEL-CUT lit) wins over the eco tint. */
    fun leftGlow(fuelCut: Boolean, ecoLevel: Int): PanelGlow = when {
        fuelCut -> PanelGlow.FUEL_CUT
        ecoLevel == 1 -> PanelGlow.ECO_GREEN
        ecoLevel in 2..3 -> PanelGlow.ECO_YELLOW
        ecoLevel >= 4 -> PanelGlow.ECO_RED
        else -> PanelGlow.NONE
    }

    /** Speed panel: overspeed (red) > harsh accel/brake (orange) > none. */
    fun rightGlow(overspeed: Boolean, harsh: Boolean): PanelGlow = when {
        overspeed -> PanelGlow.OVERSPEED
        harsh -> PanelGlow.HARSH
        else -> PanelGlow.NONE
    }

    private fun level(hasData: Boolean, warning: Boolean) = when {
        !hasData -> IndicatorLevel.OFF
        warning -> IndicatorLevel.WARNING
        else -> IndicatorLevel.NORMAL
    }

    fun decimal(v: Double?, digits: Int = 1): String =
        if (v == null || v.isNaN()) (if (digits == 0) DashboardUiState.NO_INT else DashboardUiState.NO_DECIMAL)
        else String.format(Locale.ROOT, "%.${digits}f", v)

    /** H:MM:SS like `DateUtils.formatElapsedTime` for ≥ 1 h, e.g. 0:04:29. */
    fun elapsed(seconds: Double?): String {
        if (seconds == null) return DashboardUiState.NO_TIME
        val s = seconds.toLong().coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    /**
     * The 3 × 9 Detailed Info tiles (§4.6, decision D5). A PID tile is UNSUPPORTED when polling is
     * active and the car doesn't announce the PID, NO_DATA while it hasn't been read yet.
     */
    fun detailPages(snapshot: TelemetrySnapshot?, activePids: Set<ObdPid>): List<List<DetailTileState>> {
        val v = snapshot?.values.orEmpty()
        val i = snapshot?.instant
        val trip = snapshot?.trip

        fun pid(
            title: Int, p: ObdPid, unit: String, digits: Int = 0, large: Boolean = false, icon: DetailIcon? = null,
            warning: (Double) -> Boolean = { false },
        ): DetailTileState {
            val value = v[p]
            val status = when {
                value != null -> if (warning(value)) TileStatus.WARNING else TileStatus.NORMAL
                snapshot != null && activePids.isNotEmpty() && p !in activePids -> TileStatus.UNSUPPORTED
                else -> TileStatus.NO_DATA
            }
            return DetailTileState(title, decimal(value, digits), unit, status, large, icon)
        }

        fun derived(title: Int, text: String?, unit: String, large: Boolean = false, icon: DetailIcon? = null, warn: Boolean = false) =
            DetailTileState(
                title, text ?: DashboardUiState.NO_INT, unit,
                when { text == null -> TileStatus.NO_DATA; warn -> TileStatus.WARNING; else -> TileStatus.NORMAL },
                large, icon,
            )

        val batteryWarn = i?.batteryWarning == true
        val coolantWarn = i?.coolantWarning == true
        val dtc = i?.dtcCount
        return listOf(
            listOf(
                pid(R.string.detail_accel_pedal, ObdPid.ACCELERATOR_PEDAL_D, "%"),
                pid(R.string.detail_fuel_level, ObdPid.FUEL_LEVEL, "%"),
                pid(R.string.detail_baro, ObdPid.BAROMETRIC_PRESSURE, "kPa"),
                pid(R.string.detail_speed, ObdPid.SPEED, "km/h"),
                pid(R.string.detail_rpm, ObdPid.RPM, "rpm"),
                pid(R.string.detail_engine_load, ObdPid.ENGINE_LOAD, "%"),
                pid(R.string.detail_battery, ObdPid.CONTROL_MODULE_VOLTAGE, "V", 1, true, DetailIcon.BATTERY) { batteryWarn },
                pid(R.string.detail_coolant, ObdPid.COOLANT_TEMP, "°C", 1, true, DetailIcon.COOLANT) { coolantWarn },
                derived(R.string.detail_mil, dtc?.toString(), "ea", true, DetailIcon.MIL, warn = (dtc ?: 0) > 0 || i?.milOn == true),
            ),
            listOf(
                pid(R.string.detail_st_fuel_trim, ObdPid.SHORT_FUEL_TRIM_1, "%", 1),
                pid(R.string.detail_lt_fuel_trim, ObdPid.LONG_FUEL_TRIM_1, "%", 1),
                pid(R.string.detail_fuel_pressure, ObdPid.FUEL_PRESSURE, "kPa"),
                pid(R.string.detail_intake_temp, ObdPid.INTAKE_TEMP, "°C"),
                pid(R.string.detail_ambient_temp, ObdPid.AMBIENT_TEMP, "°C"),
                pid(R.string.detail_throttle, ObdPid.THROTTLE, "%", 1),
                pid(R.string.detail_distance_mil, ObdPid.DISTANCE_WITH_MIL, "km", 0, true, DetailIcon.PIN) { it > 0 },
                pid(R.string.detail_oil_temp, ObdPid.OIL_TEMP, "°C", 0, true, DetailIcon.OIL),
                pid(R.string.detail_o2, ObdPid.O2_B1S1_VOLTAGE, "V", 2, true, DetailIcon.OBD),
            ),
            listOf(
                derived(R.string.detail_avg_economy, trip?.economyKmPerL?.let { decimal(it.coerceAtMost(TRIP_KM_PER_L_CAP)) }, "km/L"),
                derived(R.string.detail_fuel_used, trip?.let { decimal(it.fuelLitres, 2) }, "L"),
                derived(R.string.detail_co2, trip?.let { decimal(it.co2Grams / 1000.0, 2) }, "kg"),
                derived(R.string.detail_fuel_cut_time, trip?.let { elapsed(it.fuelCutS) }, ""),
                derived(R.string.detail_harsh_accel, trip?.harshAccelCount?.toString(), ""),
                derived(R.string.detail_harsh_brake, trip?.harshBrakeCount?.toString(), ""),
                derived(R.string.detail_eco_time, trip?.let { elapsed(it.ecoS) }, "", true, DetailIcon.ECO),
                derived(R.string.detail_overspeed_time, trip?.let { elapsed(it.overspeedS) }, "", true, DetailIcon.OVERSPEED),
                derived(R.string.detail_idle_time, trip?.let { elapsed(it.idleS) }, "", true, DetailIcon.IDLING),
            ),
        )
    }
}
