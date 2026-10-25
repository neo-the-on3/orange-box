package io.github.neotheon3.orangebox.core.driving.crash

/** A detected impact: the peak magnitude and when it happened (sensor clock). */
data class Impact(val tMillis: Long, val peakG: Double)

/**
 * Tells a crash-like impact from road bumps and a dropped phone, on the total acceleration magnitude.
 *
 * An impact is confirmed [CONFIRM_DELAY_MILLIS] after the magnitude first reaches [thresholdG], when:
 * - at least [MIN_SAMPLES_OVER] samples reached the threshold (one spike is a knock on the mount), and
 * - the magnitude stayed above [SUSTAIN_FRACTION] × threshold for at least [MIN_SUSTAIN_MILLIS]
 *   (potholes are sharp and short; a collision pulse lasts tens of milliseconds), and
 * - there was no free fall (< [FREE_FALL_G] for [FREE_FALL_MILLIS]) in the [FREE_FALL_LOOKBACK_MILLIS]
 *   before it: that is the phone being dropped, and the spike is it hitting the floor.
 * After an impact the detector ignores [COOLDOWN_MILLIS] so the tumbling phone can't raise a second one.
 */
class ImpactDetector(thresholdG: Double = DEFAULT_THRESHOLD_G) {
    var thresholdG: Double = thresholdG
        set(value) { require(value > 1.0) { "threshold must exceed 1 g" }; field = value }

    private val window = ArrayDeque<MotionSample>()
    private var candidateAt: Long? = null
    private var cooldownUntil = Long.MIN_VALUE

    /** Feeds one sample (in time order). Returns the impact once it is confirmed, else null. */
    fun onSample(s: MotionSample): Impact? {
        window.addLast(s)
        while (window.isNotEmpty() && s.tMillis - window.first().tMillis > WINDOW_MILLIS) window.removeFirst()
        if (s.tMillis < cooldownUntil) return null

        val start = candidateAt
        if (start == null) {
            if (s.magnitudeG >= thresholdG) candidateAt = s.tMillis
            return null
        }
        if (s.tMillis - start < CONFIRM_DELAY_MILLIS) return null
        candidateAt = null
        return evaluate(start)?.also { cooldownUntil = it.tMillis + COOLDOWN_MILLIS }
    }

    fun reset() {
        window.clear()
        candidateAt = null
        cooldownUntil = Long.MIN_VALUE
    }

    private fun evaluate(start: Long): Impact? {
        val pulse = window.filter { it.tMillis >= start - PRE_PULSE_MILLIS }
        val over = pulse.count { it.magnitudeG >= thresholdG }
        if (over < MIN_SAMPLES_OVER) return null
        if (longestRunAbove(pulse, thresholdG * SUSTAIN_FRACTION) < MIN_SUSTAIN_MILLIS) return null
        val before = window.filter { it.tMillis in (start - FREE_FALL_LOOKBACK_MILLIS) until start }
        if (longestRunBelow(before, FREE_FALL_G) >= FREE_FALL_MILLIS) return null
        val peak = pulse.maxBy { it.magnitudeG }
        return Impact(peak.tMillis, peak.magnitudeG)
    }

    private fun longestRunAbove(samples: List<MotionSample>, g: Double) = longestRun(samples) { it.magnitudeG > g }

    private fun longestRunBelow(samples: List<MotionSample>, g: Double) = longestRun(samples) { it.magnitudeG < g }

    /** Longest stretch (first to last matching sample, ms) of consecutive samples matching [match]. */
    private inline fun longestRun(samples: List<MotionSample>, match: (MotionSample) -> Boolean): Long {
        var best = 0L
        var runStart: Long? = null
        for (s in samples) {
            if (match(s)) {
                val from = runStart ?: s.tMillis.also { runStart = it }
                best = maxOf(best, s.tMillis - from)
            } else {
                runStart = null
            }
        }
        return best
    }

    companion object {
        const val DEFAULT_THRESHOLD_G = 4.0
        const val MIN_SAMPLES_OVER = 2
        const val SUSTAIN_FRACTION = 0.6
        const val MIN_SUSTAIN_MILLIS = 30L
        const val FREE_FALL_G = 0.35
        const val FREE_FALL_MILLIS = 120L
        const val FREE_FALL_LOOKBACK_MILLIS = 800L
        const val CONFIRM_DELAY_MILLIS = 250L
        const val PRE_PULSE_MILLIS = 60L
        const val WINDOW_MILLIS = 1_500L
        const val COOLDOWN_MILLIS = 30_000L
    }
}
