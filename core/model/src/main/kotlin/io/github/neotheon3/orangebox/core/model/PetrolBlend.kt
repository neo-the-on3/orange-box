package io.github.neotheon3.orangebox.core.model

/**
 * Ethanol share of pump petrol, by volume (#208). It changes the fuel estimate from MAF: E20 burns about 7.5 % more
 * litres than E0 for the same air. India sells E20 nationwide, so it is the default there; elsewhere E0.
 */
enum class PetrolBlend(val ethanolPercent: Int) {
    E0(0), E10(10), E20(20);

    companion object {
        /** Countries where E20 is the normal pump petrol. */
        private val E20_COUNTRIES = setOf("IN")

        fun forCountry(iso: String?): PetrolBlend = if (iso?.uppercase() in E20_COUNTRIES) E20 else E0
    }
}
