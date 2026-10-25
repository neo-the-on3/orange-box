package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.neotheon3.orangebox.core.dashboardui.DtcGroupIcons
import io.github.neotheon3.orangebox.core.diagnostics.DtcGroup
import io.github.neotheon3.orangebox.core.diagnostics.DtcStatus
import io.github.neotheon3.orangebox.core.diagnostics.VehicleZone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@get:StringRes
internal val DtcGroup.labelRes: Int
    get() = when (this) {
        DtcGroup.AIR_FUEL -> R.string.dtc_group_air_fuel
        DtcGroup.INJECTION -> R.string.dtc_group_injection
        DtcGroup.IGNITION -> R.string.dtc_group_ignition
        DtcGroup.SPEED -> R.string.dtc_group_speed
        DtcGroup.CIRCUITS -> R.string.dtc_group_circuits
        DtcGroup.TRANSMISSION -> R.string.dtc_group_transmission
        DtcGroup.POWERTRAIN -> R.string.dtc_group_powertrain
        DtcGroup.BODY -> R.string.dtc_group_body
        DtcGroup.CHASSIS -> R.string.dtc_group_chassis
        DtcGroup.NETWORK -> R.string.dtc_group_network
    }

internal val DtcGroup.icon: ImageVector
    get() = when (this) {
        DtcGroup.AIR_FUEL -> DtcGroupIcons.AirFuel
        DtcGroup.INJECTION -> DtcGroupIcons.Injection
        DtcGroup.IGNITION -> DtcGroupIcons.Ignition
        DtcGroup.SPEED -> DtcGroupIcons.Speed
        DtcGroup.CIRCUITS -> DtcGroupIcons.Circuits
        DtcGroup.TRANSMISSION -> DtcGroupIcons.Transmission
        DtcGroup.POWERTRAIN -> DtcGroupIcons.Powertrain
        DtcGroup.BODY -> DtcGroupIcons.Body
        DtcGroup.CHASSIS -> DtcGroupIcons.Chassis
        DtcGroup.NETWORK -> DtcGroupIcons.Network
    }

@get:StringRes
internal val VehicleZone.labelRes: Int
    get() = when (this) {
        VehicleZone.CHASSIS -> R.string.zone_chassis
        VehicleZone.TRANSMISSION -> R.string.zone_transmission
        VehicleZone.ELECTRONIC_CIRCUIT -> R.string.zone_electronic_circuit
        VehicleZone.NETWORK -> R.string.zone_network
        VehicleZone.ENGINE -> R.string.zone_engine
        VehicleZone.EXHAUST -> R.string.zone_exhaust
        VehicleZone.ELECTRONIC_DEVICE -> R.string.zone_electronic_device
    }

@get:StringRes
internal val DtcStatus.labelRes: Int
    get() = when (this) {
        DtcStatus.STORED -> R.string.dtc_status_stored
        DtcStatus.PENDING -> R.string.dtc_status_pending
        DtcStatus.PERMANENT -> R.string.dtc_status_permanent
    }

/** "Ignition: 1 trouble code" / "Ignition: no trouble codes" (#48). */
@Composable
internal fun groupA11y(group: DtcGroup, count: Int): String {
    val name = stringResource(group.labelRes)
    return if (count == 0) stringResource(R.string.dtc_group_a11y_none, name)
    else pluralStringResource(R.plurals.dtc_group_a11y, count, name, count)
}

@Composable
internal fun illustrationA11y(faulted: Set<VehicleZone>): String =
    if (faulted.isEmpty()) stringResource(R.string.illustration_a11y_ok)
    else stringResource(R.string.illustration_a11y_faults, VehicleZone.entries.filter { it in faulted }.map { stringResource(it.labelRes) }.joinToString(", "))

/** Short local date-time, e.g. "25/10/2026, 10:42". */
fun defaultTimeFormat(zone: ZoneId = ZoneId.systemDefault()): (Long) -> String {
    val f = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
    return { f.format(Instant.ofEpochMilli(it)) }
}
