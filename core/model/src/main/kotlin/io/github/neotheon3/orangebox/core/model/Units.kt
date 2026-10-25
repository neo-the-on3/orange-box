package io.github.neotheon3.orangebox.core.model

/** Distance and speed: km and km/h, or miles and mph. */
enum class DistanceUnit(val distanceLabel: String, val speedLabel: String) {
    KM("km", "km/h"),
    MI("mi", "mph"),
}

/** Fuel economy. km/L and mpg are "higher is better"; L/100 km is "lower is better". */
enum class EconomyUnit(val label: String, val shortLabel: String = label) {
    KM_PER_L("km/L"),
    L_PER_100KM("L/100 km"),
    MPG_US("mpg (US)", "mpg"),
    MPG_UK("mpg (UK)", "mpg"),
}

enum class TemperatureUnit(val label: String) {
    CELSIUS("°C"),
    FAHRENHEIT("°F"),
}

/**
 * Display units (M8, #58). Everything is stored and computed in metric (km, km/h, km/L, °C); only what
 * is shown is converted (speed, distance, economy, temperature below), so stored trips never change.
 */
data class UnitPreferences(
    val distance: DistanceUnit = DistanceUnit.KM,
    val economy: EconomyUnit = EconomyUnit.KM_PER_L,
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
) {
    val speedLabel: String get() = distance.speedLabel
    val distanceLabel: String get() = distance.distanceLabel
    val economyLabel: String get() = economy.shortLabel
    val temperatureLabel: String get() = temperature.label

    fun speed(kph: Double): Double = if (distance == DistanceUnit.MI) kph / KM_PER_MILE else kph
    fun distance(km: Double): Double = if (distance == DistanceUnit.MI) km / KM_PER_MILE else km
    fun temperature(c: Double): Double = if (temperature == TemperatureUnit.FAHRENHEIT) c * 9.0 / 5.0 + 32.0 else c

    /** Economy from km/L; null when it can't be shown (no fuel burned: L/100 km of 0 km/L is undefined). */
    fun economy(kmPerL: Double): Double? = when (economy) {
        EconomyUnit.KM_PER_L -> kmPerL
        EconomyUnit.L_PER_100KM -> if (kmPerL > 0) 100.0 / kmPerL else null
        EconomyUnit.MPG_US -> kmPerL * LITRES_PER_US_GALLON / KM_PER_MILE
        EconomyUnit.MPG_UK -> kmPerL * LITRES_PER_UK_GALLON / KM_PER_MILE
    }

    /** km/h from a speed typed or shown in the user's unit (e.g. the overspeed threshold). */
    fun speedToKph(value: Double): Double = if (distance == DistanceUnit.MI) value * KM_PER_MILE else value

    companion object {
        const val KM_PER_MILE = 1.609344
        const val LITRES_PER_US_GALLON = 3.785411784
        const val LITRES_PER_UK_GALLON = 4.54609

        /** Imperial defaults where they are the norm (US, UK and a few others); metric everywhere else. */
        fun forCountry(iso: String?): UnitPreferences = when (iso?.uppercase()) {
            "US", "PR" -> UnitPreferences(DistanceUnit.MI, EconomyUnit.MPG_US, TemperatureUnit.FAHRENHEIT)
            "LR", "MM" -> UnitPreferences(DistanceUnit.MI, EconomyUnit.MPG_US, TemperatureUnit.CELSIUS)
            "GB" -> UnitPreferences(DistanceUnit.MI, EconomyUnit.MPG_UK, TemperatureUnit.CELSIUS)
            else -> UnitPreferences()
        }
    }
}
