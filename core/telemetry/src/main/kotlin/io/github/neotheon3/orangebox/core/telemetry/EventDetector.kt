package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.common.OrangeLog
import java.util.Locale

/**
 * One kind of driving event (harsh accel, harsh brake, overspeed) with hysteresis, so jitter or a
 * stale sample can't split one sustained event into two:
 * - starts when the value reaches [startAt] and at least [rearmMillis] have passed since the last event ended;
 * - ends only once the value has stayed below [releaseBelow] for [releaseHoldMillis] (sample time);
 * - a missing value (no speed, short gap) holds the current state; [signalLost] ends it.
 * Times are sample timestamps, never arrival time.
 */
internal class EventDetector(
    private val name: String,
    private val startAt: Double,
    private val releaseBelow: Double,
    private val releaseHoldMillis: Long,
    private val rearmMillis: Long,
    private val unit: String,
    /** Extra key the end line repeats the last value under, e.g. `speed_kmh` for overspeed. */
    private val endValueKey: String? = null,
) {
    var active = false
        private set
    private var startedAt = 0L
    private var peak = 0.0
    private var belowSince: Long? = null
    private var endedAt: Long? = null

    /** Feeds one value. Returns true when a new event starts. */
    fun update(t: Long, value: Double?): Boolean {
        if (value == null) return false
        lastValue = value
        if (!active) {
            val rearmed = endedAt?.let { t - it >= rearmMillis } ?: true
            if (value >= startAt && rearmed) {
                active = true
                startedAt = t
                peak = value
                belowSince = null
                OrangeLog.i(OrangeLog.Areas.TELEMETRY, "${name}_start", "value" to fmt(value), "unit" to unit, "t_ms" to t)
                return true
            }
            return false
        }
        peak = maxOf(peak, value)
        if (value < releaseBelow) {
            val since = belowSince ?: t.also { belowSince = it }
            if (t - since >= releaseHoldMillis) end(t)
        } else {
            belowSince = null
        }
        return false
    }

    /** Last non-null value seen; logged at the end (e.g. the speed when overspeed ends). */
    private var lastValue = 0.0

    /** The signal was lost (long gap): close an open event without counting a new one. */
    fun signalLost(t: Long) {
        if (active) end(t, reason = "signal_lost")
    }

    fun reset() {
        active = false
        belowSince = null
        endedAt = null
    }

    private fun end(t: Long, reason: String = "released") {
        active = false
        endedAt = t
        belowSince = null
        val fields = buildList {
            add("duration_s" to fmt((t - startedAt) / 1000.0))
            add("peak" to fmt(peak))
            add("value" to fmt(lastValue))
            endValueKey?.let { add(it to fmt(lastValue)) }
            add("unit" to unit)
            add("reason" to reason)
        }
        OrangeLog.i(OrangeLog.Areas.TELEMETRY, "${name}_end", *fields.toTypedArray())
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
