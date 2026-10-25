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

/**
 * One entry of the small bundled catalogue; generic public figures, not a vendor database. The optional
 * fields are informational (setup hints, future range/fuel features) and aren't stored with the vehicle.
 */
data class CatalogueModel(
    val maker: String,
    val model: String,
    val fuel: VehicleFuel,
    val displacementCc: Int,
    val engineCode: String? = null,
    /** Model years sold; [toYear] null = still on sale. */
    val fromYear: Int? = null,
    val toYear: Int? = null,
    /** Liquid-fuel (petrol/diesel) tank, litres. */
    val tankLitres: Int? = null,
    /** CNG cylinder, litres water-equivalent (bi-fuel CNG models). */
    val cngLitresWaterEquivalent: Int? = null,
) {
    fun toVehicle(odometerKm: Int? = null) =
        Vehicle(name = "$maker $model", maker = maker, model = model, fuel = fuel, displacementCc = displacementCc, odometerKm = odometerKm)
}

/**
 * A deliberately small starter list; anything else is entered manually. Entries live in
 * [VehicleCatalogueData] (data only). Within a maker, models keep their listed order, so the most common
 * variant of a car comes first.
 */
object VehicleCatalogue {
    val models: List<CatalogueModel> = VehicleCatalogueData.entries

    val makers: List<String> get() = models.map { it.maker }.distinct().sorted()

    fun modelsOf(maker: String): List<CatalogueModel> = models.filter { it.maker == maker }
}
