package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitPreferencesTest {
    private val metric = UnitPreferences()
    private val us = UnitPreferences(DistanceUnit.MI, EconomyUnit.MPG_US, TemperatureUnit.FAHRENHEIT)

    @Test fun `metric is the identity`() {
        assertEquals(120.0, metric.speed(120.0), 0.0)
        assertEquals(42.5, metric.distance(42.5), 0.0)
        assertEquals(13.0, metric.economy(13.0)!!, 0.0)
        assertEquals(90.0, metric.temperature(90.0), 0.0)
        assertEquals("km/h", metric.speedLabel)
        assertEquals("km", metric.distanceLabel)
        assertEquals("km/L", metric.economyLabel)
        assertEquals("°C", metric.temperatureLabel)
    }

    @Test fun `miles and mph`() {
        assertEquals(74.565, us.speed(120.0), 1e-3)
        assertEquals(1.0, us.distance(1.609344), 1e-9)
        assertEquals(120.0, us.speedToKph(us.speed(120.0)), 1e-9)
        assertEquals("mph", us.speedLabel)
        assertEquals("mi", us.distanceLabel)
    }

    @Test fun `fahrenheit`() {
        assertEquals(212.0, us.temperature(100.0), 1e-9)
        assertEquals(32.0, us.temperature(0.0), 1e-9)
        assertEquals(-40.0, us.temperature(-40.0), 1e-9)
    }

    @Test fun `economy conversions`() {
        // 10 km/L = 10 L/100 km = 23.52 mpg (US) = 28.25 mpg (UK).
        assertEquals(10.0, metric.copy(economy = EconomyUnit.L_PER_100KM).economy(10.0)!!, 1e-9)
        assertEquals(5.0, metric.copy(economy = EconomyUnit.L_PER_100KM).economy(20.0)!!, 1e-9)
        assertEquals(23.521, us.economy(10.0)!!, 1e-3)
        assertEquals(28.248, metric.copy(economy = EconomyUnit.MPG_UK).economy(10.0)!!, 1e-3)
    }

    @Test fun `L per 100 km of no fuel economy is undefined`() =
        assertNull(metric.copy(economy = EconomyUnit.L_PER_100KM).economy(0.0))

    @Test fun `country defaults`() {
        assertEquals(UnitPreferences(), UnitPreferences.forCountry("IN"))
        assertEquals(UnitPreferences(), UnitPreferences.forCountry(null))
        assertEquals(us, UnitPreferences.forCountry("us"))
        assertEquals(EconomyUnit.MPG_UK, UnitPreferences.forCountry("GB").economy)
        assertEquals(TemperatureUnit.CELSIUS, UnitPreferences.forCountry("GB").temperature)
    }
}
