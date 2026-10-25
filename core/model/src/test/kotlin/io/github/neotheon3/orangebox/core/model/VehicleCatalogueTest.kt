package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates every bundled preset (#195), so adding a car stays a data-only change. */
class VehicleCatalogueTest {
    private val models = VehicleCatalogue.models

    @Test fun `every entry is valid`() {
        models.forEach { m ->
            val where = "${m.maker} ${m.model}"
            assertTrue(where, m.maker.isNotBlank() && m.model.isNotBlank())
            assertTrue(where, m.displacementCc in Vehicle.DISPLACEMENT_RANGE)
            m.tankLitres?.let { assertTrue(where, it in 10..200) }
            m.cngLitresWaterEquivalent?.let { assertTrue(where, it in 10..200 && m.fuel == VehicleFuel.CNG) }
            if (m.fromYear != null && m.toYear != null) assertTrue(where, m.fromYear <= m.toYear)
            m.toVehicle() // passes Vehicle's own checks
        }
    }

    @Test fun `maker and model pairs are unique`() {
        val keys = models.map { it.maker to it.model }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `Maruti Suzuki SX4 and Celerio presets, most common variant first`() {
        val sx4 = VehicleCatalogue.modelsOf("Maruti Suzuki").filter { it.model.startsWith("SX4") }
        assertEquals(listOf("SX4 1.6 VVT petrol", "SX4 1.3 DDiS diesel", "SX4 1.6 CNG"), sx4.map { it.model })
        assertEquals(1586, sx4[0].displacementCc); assertEquals(VehicleFuel.PETROL, sx4[0].fuel); assertEquals(50, sx4[0].tankLitres)
        assertEquals(1248, sx4[1].displacementCc); assertEquals(VehicleFuel.DIESEL, sx4[1].fuel)
        assertEquals(VehicleFuel.CNG, sx4[2].fuel)

        val celerio = VehicleCatalogue.modelsOf("Maruti Suzuki").filter { it.model.startsWith("Celerio") }
        assertEquals(listOf("Celerio 1.0 petrol", "Celerio 1.0 CNG", "Celerio 0.8 DDiS diesel"), celerio.map { it.model })
        assertEquals(998, celerio[0].displacementCc)
        assertEquals(60, celerio[1].cngLitresWaterEquivalent)
        assertEquals(793, celerio[2].displacementCc)
    }

    @Test fun `earlier presets keep their labels so saved vehicles still match`() {
        listOf("Swift 1.2", "Ertiga 1.5 CNG").forEach { m -> assertTrue(m, VehicleCatalogue.modelsOf("Maruti Suzuki").any { it.model == m }) }
        assertEquals("Maruti Suzuki SX4 1.6 VVT petrol", VehicleCatalogue.models.first { it.model == "SX4 1.6 VVT petrol" }.toVehicle().name)
    }
}
