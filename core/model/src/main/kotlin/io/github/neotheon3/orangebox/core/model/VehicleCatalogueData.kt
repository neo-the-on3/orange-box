package io.github.neotheon3.orangebox.core.model

import io.github.neotheon3.orangebox.core.model.VehicleFuel.CNG
import io.github.neotheon3.orangebox.core.model.VehicleFuel.DIESEL
import io.github.neotheon3.orangebox.core.model.VehicleFuel.PETROL

/**
 * The bundled vehicle presets: data only. To add a car, append a [CatalogueModel] here (keep `maker`
 * spelling identical across a maker's entries; list a car's most common variant first) and run
 * `VehicleCatalogueTest`, which checks every entry. Figures are public manufacturer / homologation data.
 * Changing an existing `maker`/`model` label orphans vehicles saved from it (they become "manual").
 */
internal object VehicleCatalogueData {
    val entries: List<CatalogueModel> = listOf(
        CatalogueModel("Ford", "Focus 1.6", PETROL, 1596),
        CatalogueModel("Honda", "City 1.5", PETROL, 1498),
        CatalogueModel("Honda", "Civic 1.8", PETROL, 1799),
        CatalogueModel("Hyundai", "Creta 1.5 diesel", DIESEL, 1493),
        CatalogueModel("Hyundai", "i20 1.2", PETROL, 1197),
        CatalogueModel("Kia", "Seltos 1.5", PETROL, 1497),
        CatalogueModel("Mahindra", "XUV700 2.2 diesel", DIESEL, 2184),
        CatalogueModel("Maruti Suzuki", "Swift 1.2", PETROL, 1197),
        CatalogueModel("Maruti Suzuki", "Ertiga 1.5 CNG", CNG, 1462),
        // SX4 sedan (India, 2007–2014; #195). Maruti Suzuki press releases 2009 (VVT, 50 L) and 2013 (1.6 VVT, 1.3 DDiS).
        CatalogueModel("Maruti Suzuki", "SX4 1.6 VVT petrol", PETROL, 1586, "M16A VVT", 2007, 2014, tankLitres = 50),
        CatalogueModel("Maruti Suzuki", "SX4 1.3 DDiS diesel", DIESEL, 1248, "D13A DDiS", 2011, 2014, tankLitres = 50),
        CatalogueModel("Maruti Suzuki", "SX4 1.6 CNG", CNG, 1586, "M16A VVT + factory CNG", 2010, 2014, tankLitres = 50),
        // Celerio (#195): K10B 2014–2021 (35 L), K10C DualJet 2021– (32 L); CNG 60 L water-equivalent; DDiS 125 2015–2017.
        CatalogueModel("Maruti Suzuki", "Celerio 1.0 petrol", PETROL, 998, "K10B / K10C", 2014, null, tankLitres = 32),
        CatalogueModel("Maruti Suzuki", "Celerio 1.0 CNG", CNG, 998, "K10B / K10C", 2014, null, tankLitres = 32, cngLitresWaterEquivalent = 60),
        CatalogueModel("Maruti Suzuki", "Celerio 0.8 DDiS diesel", DIESEL, 793, "DDiS 125 (2-cyl)", 2015, 2017, tankLitres = 35),
        CatalogueModel("Tata", "Nexon 1.2", PETROL, 1199),
        CatalogueModel("Toyota", "Corolla 1.8", PETROL, 1798),
        CatalogueModel("Toyota", "Innova 2.4 diesel", DIESEL, 2393),
        CatalogueModel("Volkswagen", "Golf 2.0 TDI", DIESEL, 1968),
        CatalogueModel("Volkswagen", "Polo 1.0", PETROL, 999),
    )
}
