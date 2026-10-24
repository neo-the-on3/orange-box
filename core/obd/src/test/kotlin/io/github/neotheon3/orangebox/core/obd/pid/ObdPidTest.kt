package io.github.neotheon3.orangebox.core.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Table-driven: one row per decoded PID, with J1979 worked examples (data bytes → value). */
@RunWith(Parameterized::class)
class ObdPidTest(private val pid: ObdPid, private val hex: String, private val expected: Double) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} = {2}")
        fun rows(): List<Array<Any>> = listOf(
            row(ObdPid.ENGINE_LOAD, "FF", 100.0), row(ObdPid.ENGINE_LOAD, "80", 50.196),
            row(ObdPid.COOLANT_TEMP, "7B", 83.0), row(ObdPid.COOLANT_TEMP, "00", -40.0),
            row(ObdPid.SHORT_FUEL_TRIM_1, "80", 0.0), row(ObdPid.SHORT_FUEL_TRIM_1, "00", -100.0),
            row(ObdPid.LONG_FUEL_TRIM_1, "FF", 99.219),
            row(ObdPid.SHORT_FUEL_TRIM_2, "90", 12.5), row(ObdPid.LONG_FUEL_TRIM_2, "70", -12.5),
            row(ObdPid.FUEL_PRESSURE, "64", 300.0),
            row(ObdPid.INTAKE_MAP, "65", 101.0),
            row(ObdPid.RPM, "1AF8", 1726.0), row(ObdPid.RPM, "0C80", 800.0),
            row(ObdPid.SPEED, "64", 100.0),
            row(ObdPid.TIMING_ADVANCE, "8E", 7.0),
            row(ObdPid.INTAKE_TEMP, "46", 30.0),
            row(ObdPid.MAF, "0190", 4.0), row(ObdPid.MAF, "FFFF", 655.35),
            row(ObdPid.THROTTLE, "33", 20.0),
            row(ObdPid.O2_B1S1_VOLTAGE, "5AFF", 0.45),
            row(ObdPid.RUN_TIME, "0E10", 3600.0),
            row(ObdPid.DISTANCE_WITH_MIL, "0100", 256.0),
            row(ObdPid.FUEL_RAIL_PRESSURE_REL, "03E8", 79.0),
            row(ObdPid.FUEL_RAIL_GAUGE_PRESSURE, "1388", 50_000.0),
            row(ObdPid.COMMANDED_EGR, "1A", 10.196),
            row(ObdPid.EGR_ERROR, "80", 0.0),
            row(ObdPid.EVAP_PURGE, "40", 25.098),
            row(ObdPid.FUEL_LEVEL, "99", 60.0),
            row(ObdPid.WARMUPS_SINCE_CLEAR, "05", 5.0),
            row(ObdPid.DISTANCE_SINCE_CLEAR, "1234", 4660.0),
            row(ObdPid.BAROMETRIC_PRESSURE, "65", 101.0),
            row(ObdPid.CATALYST_TEMP_B1S1, "1C20", 680.0),
            row(ObdPid.CATALYST_TEMP_B2S1, "0190", 0.0),
            row(ObdPid.CONTROL_MODULE_VOLTAGE, "3714", 14.1),
            row(ObdPid.ABSOLUTE_LOAD, "00FF", 100.0),
            row(ObdPid.COMMANDED_EQUIVALENCE_RATIO, "8000", 1.0),
            row(ObdPid.RELATIVE_THROTTLE, "1A", 10.196),
            row(ObdPid.AMBIENT_TEMP, "3E", 22.0),
            row(ObdPid.ABSOLUTE_THROTTLE_B, "4D", 30.196),
            row(ObdPid.ACCELERATOR_PEDAL_D, "26", 14.902),
            row(ObdPid.ACCELERATOR_PEDAL_E, "13", 7.451),
            row(ObdPid.COMMANDED_THROTTLE, "00", 0.0),
            row(ObdPid.TIME_WITH_MIL, "003C", 60.0),
            row(ObdPid.TIME_SINCE_CLEAR, "05A0", 1440.0),
            row(ObdPid.FUEL_TYPE, "04", 4.0),
            row(ObdPid.ETHANOL_PERCENT, "D9", 85.098),
            row(ObdPid.RELATIVE_PEDAL, "80", 50.196),
            row(ObdPid.HYBRID_BATTERY_REMAINING, "CC", 80.0),
            row(ObdPid.OIL_TEMP, "82", 90.0),
            row(ObdPid.FUEL_RATE, "00C8", 10.0),
            row(ObdPid.FUEL_SYSTEM_STATUS, "0200", 2.0),
        )

        private fun row(pid: ObdPid, hex: String, v: Double) = arrayOf<Any>(pid, hex, v)
    }

    @Test fun decodes() {
        val data = hex.chunked(2).map { it.toInt(16) }.toIntArray()
        assertEquals(expected, pid.decodeOrNull(data)!!, 0.001)
        assertNull("short frames are rejected", pid.decodeOrNull(data.copyOf(pid.bytes - 1)))
    }
}

class ObdPidTableTest {
    @Test fun `every pid has a row in the table and a unique code`() {
        val covered = ObdPidTest.rows().map { it[0] as ObdPid }.toSet()
        assertEquals(ObdPid.entries.toSet(), covered)
        assertEquals(ObdPid.entries.size, ObdPid.entries.map { it.pid }.toSet().size)
        assertEquals("010C", ObdPid.RPM.command)
    }
}
