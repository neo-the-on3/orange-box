package io.github.neotheon3.orangebox.core.diagnostics

/** The seven highlightable zones of the vehicle-systems illustration (docs/ui/LEGACY_OBD_UI.md §5.1). */
enum class VehicleZone { CHASSIS, TRANSMISSION, ELECTRONIC_CIRCUIT, NETWORK, ENGINE, EXHAUST, ELECTRONIC_DEVICE }

/**
 * The ten DTC-group lamps of the diagnostics screen, in legacy lamp order (two rows of five), with the
 * legacy code ranges and the illustration zone each one lights (§5.1 table).
 */
enum class DtcGroup(val label: String, val zone: VehicleZone) {
    AIR_FUEL("Air/Fuel", VehicleZone.EXHAUST),
    INJECTION("Injection", VehicleZone.ENGINE),
    IGNITION("Ignition", VehicleZone.ENGINE),
    SPEED("Speed", VehicleZone.ENGINE),
    CIRCUITS("Circuits", VehicleZone.ELECTRONIC_CIRCUIT),
    TRANSMISSION("Transmission", VehicleZone.TRANSMISSION),
    POWERTRAIN("Powertrain", VehicleZone.ENGINE),
    BODY("Body", VehicleZone.ELECTRONIC_DEVICE),
    CHASSIS("Chassis", VehicleZone.CHASSIS),
    NETWORK("Network", VehicleZone.NETWORK),
    ;

    companion object {
        private val pattern = Regex("^[PCBU][0-3][0-9A-F]{3}$")

        /**
         * Group of [code] (`P0300` → IGNITION), or null when it isn't a well-formed SAE code.
         * Powertrain codes follow the legacy `DtcParser` ranges: P0001–P0199 and P0400–P0499 Air/Fuel,
         * P02xx Injection, P03xx Ignition, P05xx Speed, P06xx Circuits, P07xx–P08xx Transmission and
         * P1000 and up Powertrain. Two gaps in the legacy table are filled by SAE J2012: P09xx is
         * transmission, and P0000 plus the hybrid P0Axx–P0Fxx block go to Powertrain.
         */
        fun of(code: String): DtcGroup? {
            val c = code.trim().uppercase()
            if (!pattern.matches(c)) return null
            return when (c[0]) {
                'B' -> BODY
                'C' -> CHASSIS
                'U' -> NETWORK
                else -> powertrain(c)
            }
        }

        private fun powertrain(c: String): DtcGroup {
            if (c[1] != '0') return POWERTRAIN // P1xxx, P2xxx, P3xxx
            val hundreds = c[2]
            if (hundreds !in '0'..'9') return POWERTRAIN // P0Axx–P0Fxx (hybrid)
            val n = c.substring(2).toIntOrNull() ?: return POWERTRAIN
            return when (n) {
                0 -> POWERTRAIN
                in 1..199, in 400..499 -> AIR_FUEL
                in 200..299 -> INJECTION
                in 300..399 -> IGNITION
                in 500..599 -> SPEED
                in 600..699 -> CIRCUITS
                in 700..999 -> TRANSMISSION
                else -> POWERTRAIN
            }
        }
    }
}

/** Which list a code was read from. */
enum class DtcStatus(val wire: String) {
    /** Mode 03: confirmed, usually with the MIL on. */
    STORED("stored"),
    /** Mode 07: seen on the current or last drive cycle, not confirmed yet. */
    PENDING("pending"),
    /** Mode 0A: cannot be cleared with mode 04; the ECU removes it after it verifies the repair. */
    PERMANENT("permanent"),
    ;

    companion object {
        fun fromWire(wire: String): DtcStatus = entries.firstOrNull { it.wire == wire } ?: STORED
    }
}

/** One code on the diagnostics screen. */
data class TroubleCode(val code: String, val status: DtcStatus) {
    val group: DtcGroup? get() = DtcGroup.of(code)
    val description: String get() = DtcCatalog.describe(code)
}
