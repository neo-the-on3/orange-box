package io.github.neotheon3.orangebox.core.telemetry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Totals of the trip being recorded, as persisted by the trip recorder (carried across a reconnect inside
 * the engine-off grace), or null when no trip is recording. The ongoing notification and `trip_end` use
 * the same totals; the dashboard prefers them over [TelemetrySnapshot.trip], which the engine zeroes on
 * every reconnect (#144).
 */
fun interface RecordedTripTotals {
    fun totals(): Flow<TripTotals?>

    companion object {
        val NONE = RecordedTripTotals { flowOf(null) }
    }
}
