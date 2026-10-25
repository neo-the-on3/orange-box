package io.github.neotheon3.orangebox.core.driving.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactDetectorTest {
    /** Traces in src/test/resources/motion (synthetic, 50 Hz, magnitude in g). */
    private fun trace(name: String): List<MotionSample> =
        javaClass.getResourceAsStream("/motion/$name.csv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(',').let { MotionSample.ofG(it[0].toLong(), it[1].toDouble()) } }

    private fun run(samples: List<MotionSample>, threshold: Double = ImpactDetector.DEFAULT_THRESHOLD_G): List<Impact> {
        val d = ImpactDetector(threshold)
        return samples.mapNotNull(d::onSample)
    }

    @Test fun `collision trace is detected once, at its peak`() {
        val impacts = run(trace("impact"))
        assertEquals(1, impacts.size)
        assertTrue("peak ${impacts[0].peakG}", impacts[0].peakG > 8.5)
        assertTrue(impacts[0].tMillis in 2000..2120)
    }

    @Test fun `potholes are not an impact`() = assertTrue(run(trace("pothole")).isEmpty())

    @Test fun `a single knock on the mount is not an impact`() = assertTrue(run(trace("knock")).isEmpty())

    @Test fun `a dropped phone hitting the floor is not an impact`() = assertTrue(run(trace("phone_drop")).isEmpty())

    @Test fun `even at the most sensitive threshold potholes and a drop are rejected`() {
        assertTrue(run(trace("pothole"), threshold = 2.5).isEmpty())
        assertTrue(run(trace("phone_drop"), threshold = 2.5).isEmpty())
        assertEquals(1, run(trace("impact"), threshold = 2.5).size)
    }

    @Test fun `a higher threshold ignores the collision pulse`() = assertTrue(run(trace("impact"), threshold = 9.5).isEmpty())

    @Test fun `cooldown keeps a tumbling phone from raising a second impact`() {
        val first = trace("impact")
        val second = first.map { it.copy(tMillis = it.tMillis + 10_000) } // 10 s later: inside the 30 s cooldown
        val third = first.map { it.copy(tMillis = it.tMillis + 40_000) } // 40 s later: re-armed
        val impacts = run(first + second + third)
        assertEquals(2, impacts.size)
        assertTrue(impacts[1].tMillis >= 40_000)
    }

    @Test fun `reset clears a pending candidate`() {
        val d = ImpactDetector()
        trace("impact").filter { it.tMillis <= 2100 }.forEach { assertNull(d.onSample(it)) }
        d.reset()
        assertTrue((2120L..3000L step 20).mapNotNull { d.onSample(MotionSample.ofG(it, 1.0)) }.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun `threshold must exceed 1 g`() {
        ImpactDetector().thresholdG = 0.9
    }

    @Test fun `magnitude includes all axes`() {
        val s = MotionSample(0, 3f, 4f, 0f)
        assertEquals(5.0 / MotionSample.STANDARD_GRAVITY, s.magnitudeG, 1e-6)
    }
}
