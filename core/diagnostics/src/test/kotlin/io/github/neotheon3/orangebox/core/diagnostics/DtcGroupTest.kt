package io.github.neotheon3.orangebox.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTC → group mapping per docs/ui/LEGACY_OBD_UI.md §5.1 (#48). */
class DtcGroupTest {
    private fun assertGroup(group: DtcGroup, vararg codes: String) =
        codes.forEach { assertEquals(it, group, DtcGroup.of(it)) }

    @Test fun `legacy ranges map to their lamps`() {
        assertGroup(DtcGroup.AIR_FUEL, "P0001", "P0133", "P0171", "P0199", "P0400", "P0420", "P0499")
        assertGroup(DtcGroup.INJECTION, "P0200", "P0201", "P0299")
        assertGroup(DtcGroup.IGNITION, "P0300", "P0301", "P0399")
        assertGroup(DtcGroup.SPEED, "P0500", "P0507", "P0599")
        assertGroup(DtcGroup.CIRCUITS, "P0600", "P0650", "P0699")
        assertGroup(DtcGroup.TRANSMISSION, "P0700", "P0750", "P0899")
        assertGroup(DtcGroup.POWERTRAIN, "P1000", "P1234", "P2096", "P3000")
        assertGroup(DtcGroup.BODY, "B0001", "B1234")
        assertGroup(DtcGroup.CHASSIS, "C0035", "C1234")
        assertGroup(DtcGroup.NETWORK, "U0100", "U3FFF")
    }

    @Test fun `legacy gaps are filled from SAE J2012`() {
        assertGroup(DtcGroup.TRANSMISSION, "P0900", "P0999")
        assertGroup(DtcGroup.POWERTRAIN, "P0000", "P0A80", "P0F00")
    }

    @Test fun `codes are normalised and malformed ones have no group`() {
        assertEquals(DtcGroup.IGNITION, DtcGroup.of(" p0300 "))
        listOf("", "P030", "P03000", "X0300", "P4000", "P0G00").forEach { assertNull(it, DtcGroup.of(it)) }
    }

    @Test fun `lamps light the legacy illustration zones`() {
        assertEquals(VehicleZone.EXHAUST, DtcGroup.AIR_FUEL.zone)
        assertEquals(VehicleZone.ENGINE, DtcGroup.IGNITION.zone)
        assertEquals(VehicleZone.ELECTRONIC_CIRCUIT, DtcGroup.CIRCUITS.zone)
        assertEquals(VehicleZone.TRANSMISSION, DtcGroup.TRANSMISSION.zone)
        assertEquals(VehicleZone.ELECTRONIC_DEVICE, DtcGroup.BODY.zone)
        assertEquals(VehicleZone.CHASSIS, DtcGroup.CHASSIS.zone)
        assertEquals(VehicleZone.NETWORK, DtcGroup.NETWORK.zone)
        // Every zone is reachable from some group.
        assertEquals(VehicleZone.entries.toSet(), DtcGroup.entries.map { it.zone }.toSet())
        assertEquals(10, DtcGroup.entries.size)
    }

    @Test fun `known codes have their own text, others a generic fallback`() {
        assertTrue(DtcCatalog.isKnown("P0300"))
        assertEquals("Random or multiple cylinder misfire detected.", DtcCatalog.describe("P0300"))
        assertEquals("Misfire detected in cylinder 4.", DtcCatalog.describe("P0304"))
        assertEquals("Fuel injector circuit, cylinder 2.", DtcCatalog.describe("P0202"))
        assertEquals("Catalytic converter efficiency is below the limit (bank 1).", DtcCatalog.describe("p0420"))
        assertEquals("Lost communication with the engine control module.", DtcCatalog.describe("U0100"))

        assertFalse(DtcCatalog.isKnown("P0399"))
        assertEquals("Generic code: ignition system or misfire.", DtcCatalog.describe("P0399"))
        assertEquals("Generic code: transmission.", DtcCatalog.describe("P0911"))
        assertEquals("Generic hybrid propulsion code.", DtcCatalog.describe("P0A80"))
        assertEquals("Generic powertrain code (fuel, air or emission control).", DtcCatalog.describe("P2999"))
        val maker = "Manufacturer-specific code. Check the service information for your car."
        listOf("P1234", "P3000", "B1234", "C2000", "U1100").forEach { assertEquals(it, maker, DtcCatalog.describe(it)) }
        assertEquals("Generic chassis code.", DtcCatalog.describe("C0999"))
        assertEquals(DtcCatalog.UNKNOWN, DtcCatalog.describe("hello"))
    }

    @Test fun `every group has its own description`() {
        val texts = DtcGroup.entries.map { DtcCatalog.groupDescription(it) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun `report groups codes and only offers clear for stored or pending`() {
        val report = DiagnosticsReport(
            readAtMillis = 0,
            codes = listOf(TroubleCode("P0300", DtcStatus.STORED), TroubleCode("P0420", DtcStatus.PENDING), TroubleCode("U0100", DtcStatus.STORED)),
            milOn = true, vin = null,
        )
        assertEquals(setOf(DtcGroup.IGNITION, DtcGroup.AIR_FUEL, DtcGroup.NETWORK), report.faultedGroups)
        assertEquals(setOf(VehicleZone.ENGINE, VehicleZone.EXHAUST, VehicleZone.NETWORK), report.faultedZones)
        assertTrue(report.hasClearableCodes)
        val permanentOnly = report.copy(codes = listOf(TroubleCode("P0420", DtcStatus.PERMANENT)), milOn = false)
        assertFalse(permanentOnly.hasClearableCodes)
        assertTrue(permanentOnly.hasTroubles)
        assertTrue(report.copy(codes = emptyList(), milOn = true).hasTroubles)
        assertFalse(report.copy(codes = emptyList(), milOn = false).hasTroubles)
    }
}
