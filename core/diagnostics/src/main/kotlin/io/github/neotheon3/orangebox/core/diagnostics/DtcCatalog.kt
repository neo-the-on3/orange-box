package io.github.neotheon3.orangebox.core.diagnostics

/**
 * Plain-English descriptions of common generic (SAE J2012 "0" series) trouble codes, plus a fallback
 * for every other code.
 *
 * Provenance: written for Orange Box from general OBD-II knowledge, in our own words. No text is copied
 * from the legacy app, from SAE documents or from any other code database. Codes are standard
 * identifiers; only the short explanations are ours. English only for now (localisation scaffold: M10).
 */
object DtcCatalog {
    /** Shown under every group in the detail sheet (legacy wording kept: it's a generic recommendation). */
    const val RECOMMENDATION = "Car maintenance and inspection recommended."

    fun describe(code: String): String {
        val c = code.trim().uppercase()
        return KNOWN[c] ?: fallback(c)
    }

    /** True when [code] has its own entry (not the generic fallback). */
    fun isKnown(code: String): Boolean = code.trim().uppercase() in KNOWN

    /** What a group covers, for the detail sheet. */
    fun groupDescription(group: DtcGroup): String = when (group) {
        DtcGroup.AIR_FUEL -> "Sensors and controls that meter air and fuel and clean the exhaust: airflow and " +
            "pressure sensors, oxygen sensors, EGR, the evaporative system and the catalytic converter."
        DtcGroup.INJECTION -> "Fuel injectors and their circuits, fuel pump control, and turbo or supercharger boost."
        DtcGroup.IGNITION -> "Spark and misfire detection: ignition coils, knock sensors, and the crankshaft and " +
            "camshaft position sensors that time the engine."
        DtcGroup.SPEED -> "Vehicle speed, idle speed control and related engine inputs such as oil pressure, " +
            "brake switch and system voltage."
        DtcGroup.CIRCUITS -> "The engine control module itself and its outputs: memory, processor, sensor " +
            "reference voltages, the charging system and warning-lamp circuits."
        DtcGroup.TRANSMISSION -> "Automatic transmission sensors, shift solenoids, torque converter clutch and " +
            "fluid pressure."
        DtcGroup.POWERTRAIN -> "Other powertrain codes: newer generic codes (P2xxx), hybrid systems (P0Axx) and " +
            "manufacturer-specific codes (P1xxx, P3xxx)."
        DtcGroup.BODY -> "Body systems such as airbags and restraints, seats, lighting, locks and climate control."
        DtcGroup.CHASSIS -> "Brakes (ABS), traction and stability control, steering and suspension."
        DtcGroup.NETWORK -> "The data network between the car's control modules: a module that stopped " +
            "answering, or invalid data on the bus."
    }

    /** Generic text by SAE range, for codes without their own entry. */
    internal fun fallback(c: String): String {
        if (c.length != 5) return UNKNOWN
        val generic = c[1] == '0' || (c[0] == 'P' && c[1] == '2')
        val maker = "Manufacturer-specific code. Check the service information for your car."
        return when (c[0]) {
            'P' -> when {
                !generic -> maker
                c[1] == '2' -> "Generic powertrain code (fuel, air or emission control)."
                c[2] == 'A' -> "Generic hybrid propulsion code."
                c[2] in '0'..'1' -> "Generic code: fuel and air metering."
                c[2] == '2' -> "Generic code: fuel and air metering (injector circuit)."
                c[2] == '3' -> "Generic code: ignition system or misfire."
                c[2] == '4' -> "Generic code: auxiliary emission controls."
                c[2] == '5' -> "Generic code: vehicle speed, idle control or auxiliary inputs."
                c[2] == '6' -> "Generic code: control module or its output circuits."
                c[2] in '7'..'9' -> "Generic code: transmission."
                else -> "Generic powertrain code."
            }
            'B' -> if (generic) "Generic body code." else maker
            'C' -> if (generic) "Generic chassis code." else maker
            'U' -> if (generic) "Generic network communication code." else maker
            else -> UNKNOWN
        }
    }

    const val UNKNOWN = "Unknown trouble code."

    private fun cylinders(prefix: String, from: Int, text: (Int) -> String) =
        (1..8).associate { n -> "%s%02d".format(prefix, from + n - 1).let { "P0$it" } to text(n) }

    private val KNOWN: Map<String, String> = buildMap {
        // Fuel and air metering, auxiliary emission controls.
        put("P0010", "Intake camshaft timing actuator circuit (bank 1).")
        put("P0011", "Intake camshaft timing is more advanced than commanded (bank 1).")
        put("P0016", "Crankshaft and camshaft position signals don't line up (bank 1).")
        put("P0030", "Upstream oxygen sensor heater control circuit (bank 1).")
        put("P0068", "Manifold pressure or throttle position doesn't match the measured airflow.")
        put("P0087", "Fuel rail pressure is too low.")
        put("P0088", "Fuel rail pressure is too high.")
        put("P0100", "Mass airflow sensor circuit.")
        put("P0101", "Mass airflow sensor reading is out of the expected range.")
        put("P0102", "Mass airflow sensor signal is too low.")
        put("P0103", "Mass airflow sensor signal is too high.")
        put("P0105", "Manifold or barometric pressure sensor circuit.")
        put("P0106", "Manifold pressure sensor reading is out of the expected range.")
        put("P0107", "Manifold pressure sensor signal is too low.")
        put("P0108", "Manifold pressure sensor signal is too high.")
        put("P0110", "Intake air temperature sensor circuit.")
        put("P0112", "Intake air temperature sensor signal is too low.")
        put("P0113", "Intake air temperature sensor signal is too high.")
        put("P0115", "Engine coolant temperature sensor circuit.")
        put("P0116", "Coolant temperature sensor reading is out of the expected range.")
        put("P0117", "Coolant temperature sensor signal is too low.")
        put("P0118", "Coolant temperature sensor signal is too high.")
        put("P0120", "Throttle position sensor A circuit.")
        put("P0121", "Throttle position sensor A reading is out of the expected range.")
        put("P0122", "Throttle position sensor A signal is too low.")
        put("P0123", "Throttle position sensor A signal is too high.")
        put("P0125", "The engine takes too long to warm up for closed-loop fuel control.")
        put("P0128", "Coolant stays below the thermostat's regulating temperature (thermostat stuck open?).")
        put("P0130", "Upstream oxygen sensor circuit (bank 1, sensor 1).")
        put("P0131", "Upstream oxygen sensor voltage is too low (bank 1, sensor 1).")
        put("P0132", "Upstream oxygen sensor voltage is too high (bank 1, sensor 1).")
        put("P0133", "Upstream oxygen sensor responds too slowly (bank 1, sensor 1).")
        put("P0134", "Upstream oxygen sensor shows no activity (bank 1, sensor 1).")
        put("P0135", "Upstream oxygen sensor heater circuit (bank 1, sensor 1).")
        put("P0136", "Downstream oxygen sensor circuit (bank 1, sensor 2).")
        put("P0137", "Downstream oxygen sensor voltage is too low (bank 1, sensor 2).")
        put("P0138", "Downstream oxygen sensor voltage is too high (bank 1, sensor 2).")
        put("P0140", "Downstream oxygen sensor shows no activity (bank 1, sensor 2).")
        put("P0141", "Downstream oxygen sensor heater circuit (bank 1, sensor 2).")
        put("P0150", "Upstream oxygen sensor circuit (bank 2, sensor 1).")
        put("P0155", "Upstream oxygen sensor heater circuit (bank 2, sensor 1).")
        put("P0171", "The engine runs too lean (bank 1): too much air or too little fuel.")
        put("P0172", "The engine runs too rich (bank 1): too much fuel or too little air.")
        put("P0174", "The engine runs too lean (bank 2).")
        put("P0175", "The engine runs too rich (bank 2).")
        put("P0190", "Fuel rail pressure sensor circuit.")
        put("P0191", "Fuel rail pressure sensor reading is out of the expected range.")
        // Injector circuit, fuel pump, boost.
        put("P0200", "Fuel injector circuit.")
        putAll(cylinders("2", 1) { "Fuel injector circuit, cylinder $it." })
        put("P0217", "Engine over-temperature condition.")
        put("P0219", "Engine over-speed condition.")
        put("P0230", "Fuel pump primary circuit.")
        put("P0234", "Turbo or supercharger overboost.")
        put("P0299", "Turbo or supercharger underboost.")
        // Ignition and misfire.
        put("P0300", "Random or multiple cylinder misfire detected.")
        putAll(cylinders("3", 1) { "Misfire detected in cylinder $it." })
        put("P0315", "Crankshaft position variation has not been learned.")
        put("P0325", "Knock sensor 1 circuit (bank 1).")
        put("P0327", "Knock sensor 1 signal is too low (bank 1).")
        put("P0328", "Knock sensor 1 signal is too high (bank 1).")
        put("P0335", "Crankshaft position sensor A circuit.")
        put("P0336", "Crankshaft position sensor A reading is out of the expected range.")
        put("P0340", "Camshaft position sensor A circuit (bank 1).")
        put("P0341", "Camshaft position sensor A reading is out of the expected range (bank 1).")
        putAll((1..6).associate { "P035$it" to "Ignition coil $it circuit." })
        // Auxiliary emission controls.
        put("P0400", "Exhaust gas recirculation (EGR) flow problem.")
        put("P0401", "EGR flow is lower than expected.")
        put("P0402", "EGR flow is higher than expected.")
        put("P0403", "EGR control circuit.")
        put("P0404", "EGR system reading is out of the expected range.")
        put("P0410", "Secondary air injection system.")
        put("P0411", "Secondary air injection flow is not as expected.")
        put("P0420", "Catalytic converter efficiency is below the limit (bank 1).")
        put("P0421", "Warm-up catalytic converter efficiency is below the limit (bank 1).")
        put("P0430", "Catalytic converter efficiency is below the limit (bank 2).")
        put("P0440", "Evaporative emission (fuel vapour) system.")
        put("P0441", "Evaporative system purge flow is not as expected.")
        put("P0442", "Small leak in the evaporative emission system.")
        put("P0443", "Evaporative system purge valve circuit.")
        put("P0446", "Evaporative system vent control circuit.")
        put("P0455", "Large leak in the evaporative emission system (check the fuel cap).")
        put("P0456", "Very small leak in the evaporative emission system.")
        put("P0457", "Evaporative system leak: fuel cap loose or missing.")
        put("P0461", "Fuel level sensor reading is out of the expected range.")
        put("P0480", "Cooling fan 1 control circuit.")
        put("P0491", "Secondary air injection system (bank 1).")
        // Speed, idle, auxiliary inputs.
        put("P0500", "Vehicle speed sensor A.")
        put("P0501", "Vehicle speed sensor A reading is out of the expected range.")
        put("P0505", "Idle air control system.")
        put("P0506", "Idle speed is lower than expected.")
        put("P0507", "Idle speed is higher than expected.")
        put("P0520", "Engine oil pressure sensor or switch circuit.")
        put("P0521", "Engine oil pressure reading is out of the expected range.")
        put("P0562", "System voltage is too low.")
        put("P0563", "System voltage is too high.")
        put("P0571", "Brake switch A circuit.")
        // Control module and outputs.
        put("P0600", "Serial communication link inside the control module.")
        put("P0601", "Control module memory checksum error.")
        put("P0602", "Control module is not programmed.")
        put("P0603", "Control module keep-alive memory error.")
        put("P0604", "Control module RAM error.")
        put("P0605", "Control module ROM error.")
        put("P0606", "Control module processor fault.")
        put("P0620", "Alternator (generator) control circuit.")
        put("P0641", "Sensor reference voltage A circuit.")
        put("P0650", "Check-engine lamp (MIL) control circuit.")
        // Transmission.
        put("P0700", "Transmission control system fault (the transmission module has its own code).")
        put("P0705", "Transmission range (gear selector) sensor circuit.")
        put("P0715", "Transmission input or turbine speed sensor circuit.")
        put("P0720", "Transmission output speed sensor circuit.")
        put("P0730", "Incorrect gear ratio.")
        put("P0740", "Torque converter clutch circuit.")
        put("P0741", "Torque converter clutch stays disengaged.")
        put("P0750", "Shift solenoid A.")
        put("P0755", "Shift solenoid B.")
        put("P0841", "Transmission fluid pressure sensor or switch A reading is out of range.")
        put("P0868", "Transmission fluid pressure is too low.")
        // Newer generic powertrain.
        put("P2096", "Fuel trim after the catalytic converter is too lean (bank 1).")
        put("P2097", "Fuel trim after the catalytic converter is too rich (bank 1).")
        put("P2135", "Throttle position sensors A and B disagree.")
        put("P2138", "Accelerator pedal position sensors D and E disagree.")
        put("P2187", "The engine runs too lean at idle (bank 1).")
        put("P2195", "Upstream oxygen sensor signal is stuck lean (bank 1, sensor 1).")
        put("P2196", "Upstream oxygen sensor signal is stuck rich (bank 1, sensor 1).")
        put("P2270", "Downstream oxygen sensor signal is stuck lean (bank 1, sensor 2).")
        // Chassis.
        put("C0035", "Left front wheel speed sensor circuit.")
        put("C0040", "Right front wheel speed sensor circuit.")
        put("C0045", "Left rear wheel speed sensor circuit.")
        put("C0050", "Right rear wheel speed sensor circuit.")
        // Body.
        put("B0001", "Driver front airbag, stage 1 deployment control.")
        // Network.
        put("U0001", "High-speed CAN data bus communication.")
        put("U0100", "Lost communication with the engine control module.")
        put("U0101", "Lost communication with the transmission control module.")
        put("U0121", "Lost communication with the ABS control module.")
        put("U0140", "Lost communication with the body control module.")
        put("U0155", "Lost communication with the instrument cluster.")
        put("U0401", "Invalid data received from the engine control module.")
    }
}
