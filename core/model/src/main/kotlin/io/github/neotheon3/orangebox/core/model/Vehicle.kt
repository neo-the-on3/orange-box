package io.github.neotheon3.orangebox.core.model

/** Fuel as the user describes the car. The ECU's own report (PID 51) still wins when present. */
enum class VehicleFuel { PETROL, DIESEL, LPG, CNG, ETHANOL }

/** A car the user drives. [id] is 0 until stored. */
data class Vehicle(
    val id: Long = 0,
    val name: String,
    val maker: String? = null,
    val model: String? = null,
    val fuel: VehicleFuel = VehicleFuel.PETROL,
    /** Engine displacement, cc; used by the speed-density fuel estimate when the car has no MAF. */
    val displacementCc: Int? = null,
    val odometerKm: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(displacementCc == null || displacementCc in DISPLACEMENT_RANGE) { "displacement out of range" }
        require(odometerKm == null || odometerKm in 0..ODOMETER_MAX) { "odometer out of range" }
    }

    companion object {
        val DISPLACEMENT_RANGE = 50..10_000
        const val ODOMETER_MAX = 2_000_000
    }
}

/** One entry of the small bundled catalogue; generic public figures, not a vendor database. */
data class CatalogueModel(val maker: String, val model: String, val fuel: VehicleFuel, val displacementCc: Int) {
    fun toVehicle(odometerKm: Int? = null) =
        Vehicle(name = "$maker $model", maker = maker, model = model, fuel = fuel, displacementCc = displacementCc, odometerKm = odometerKm)
}

/** A deliberately small starter list; anything else is entered manually. */
object VehicleCatalogue {
    val models: List<CatalogueModel> = listOf(
        CatalogueModel("Ford", "Focus 1.6", VehicleFuel.PETROL, 1596),
        CatalogueModel("Honda", "City 1.5", VehicleFuel.PETROL, 1498),
        CatalogueModel("Honda", "Civic 1.8", VehicleFuel.PETROL, 1799),
        CatalogueModel("Hyundai", "Creta 1.5 diesel", VehicleFuel.DIESEL, 1493),
        CatalogueModel("Hyundai", "i20 1.2", VehicleFuel.PETROL, 1197),
        CatalogueModel("Kia", "Seltos 1.5", VehicleFuel.PETROL, 1497),
        CatalogueModel("Mahindra", "XUV700 2.2 diesel", VehicleFuel.DIESEL, 2184),
        CatalogueModel("Maruti Suzuki", "Swift 1.2", VehicleFuel.PETROL, 1197),
        CatalogueModel("Maruti Suzuki", "Ertiga 1.5 CNG", VehicleFuel.CNG, 1462),
        CatalogueModel("Tata", "Nexon 1.2", VehicleFuel.PETROL, 1199),
        CatalogueModel("Toyota", "Corolla 1.8", VehicleFuel.PETROL, 1798),
        CatalogueModel("Toyota", "Innova 2.4 diesel", VehicleFuel.DIESEL, 2393),
        CatalogueModel("Volkswagen", "Golf 2.0 TDI", VehicleFuel.DIESEL, 1968),
        CatalogueModel("Volkswagen", "Polo 1.0", VehicleFuel.PETROL, 999),
    )

    val makers: List<String> get() = models.map { it.maker }.distinct().sorted()

    fun modelsOf(maker: String): List<CatalogueModel> = models.filter { it.maker == maker }
}
