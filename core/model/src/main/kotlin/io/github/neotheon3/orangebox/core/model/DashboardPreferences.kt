package io.github.neotheon3.orangebox.core.model

/** What the dashboard's bottom-left button does. */
enum class DashboardButton {
    /** Emergency prompt with the dialer prefilled; never calls by itself. */
    HAZARD,

    /** Launches the app the user picked ("Quick launcher" in the legacy app). */
    QUICK_LAUNCHER,
}

/** Driving-dashboard choices from Settings. */
data class DashboardPreferences(
    val button: DashboardButton = DashboardButton.HAZARD,
    /** Number the hazard prompt dials; null = the default for the user's country ([EmergencyNumbers]). */
    val emergencyNumber: String? = null,
    /** Package the quick launcher opens; null = not chosen yet (the button then acts as hazard). */
    val quickLaunchPackage: String? = null,
    /** Overspeed starts above this; it ends below this − 3 km/h. */
    val overspeedKph: Int = DEFAULT_OVERSPEED_KPH,
    /** Bring the dashboard up on dongle connect, including over the lock screen. Off by default. */
    val autoShowDashboard: Boolean = false,
    /**
     * Display-only speed offset, % (#210). Orange Box shows the car's true (ECU) speed; car speedometers read a few
     * percent high by design. 0 by default. Overspeed, trips and stats always use the true speed.
     */
    val speedometerOffsetPercent: Int = 0,
) {
    /** [trueKph] as shown on the dashboard, with the speedometer offset applied. */
    fun displaySpeed(trueKph: Double): Double = trueKph * (1 + speedometerOffsetPercent / 100.0)

    companion object {
        const val MAX_SPEEDOMETER_OFFSET_PERCENT = 10
        fun clampSpeedometerOffset(percent: Int) = percent.coerceIn(0, MAX_SPEEDOMETER_OFFSET_PERCENT)

        const val DEFAULT_OVERSPEED_KPH = 120
        const val MIN_OVERSPEED_KPH = 40
        const val MAX_OVERSPEED_KPH = 200
        const val OVERSPEED_STEP_KPH = 5
        const val OVERSPEED_HYSTERESIS_KPH = 3

        fun clampOverspeed(kph: Int) = kph.coerceIn(MIN_OVERSPEED_KPH, MAX_OVERSPEED_KPH)

        /** Digits plus the dialer's + * #, 2–15 characters. */
        fun isValidEmergencyNumber(number: String) = number.length in 2..15 && number.all { it.isDigit() || it in "+*#" }
    }
}

/** Default emergency number by ISO 3166 country (upper case). 112 is the fallback (EU, India, GSM). */
object EmergencyNumbers {
    const val FALLBACK = "112"

    private val byCountry: Map<String, String> = buildMap {
        listOf("US", "CA", "MX", "PR", "PA", "CR", "SV", "GT", "HN", "AR", "VE", "PH").forEach { put(it, "911") }
        listOf("GB", "IE", "HK", "MO", "SG", "MY", "BD", "AE", "QA", "BH", "SA", "KE", "GH", "NG", "ZW", "MU").forEach { put(it, "999") }
        put("IE", "112") // 112 and 999 both work; 112 is the EU number
        put("AU", "000")
        put("NZ", "111")
        put("JP", "110"); put("KR", "112"); put("TW", "110"); put("CN", "110")
        put("BR", "190"); put("CL", "133"); put("CO", "123")
        put("ZA", "10111")
        put("IN", "112"); put("PK", "15"); put("LK", "119"); put("NP", "100")
        put("ID", "112"); put("TH", "191"); put("VN", "113")
        put("IL", "100"); put("EG", "122"); put("TR", "112")
        put("RU", "112"); put("UA", "112")
    }

    fun forCountry(iso: String?): String = iso?.uppercase()?.let(byCountry::get) ?: FALLBACK
}
