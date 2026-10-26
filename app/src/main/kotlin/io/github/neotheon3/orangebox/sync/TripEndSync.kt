package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.TripRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a one-off sync when a trip ends (its summary and telemetry are then complete), only while sync is on
 * ([SyncEligibility]: Fake, or Remote while signed in). Off never touches WorkManager. The scheduler keeps a
 * one-off that is already queued, delayed by `Retry-After` or backing off, and skips while the periodic sync
 * backs off, so a trip end never cuts a backoff short.
 */
@Singleton
class TripEndSync(
    private val scope: CoroutineScope,
    private val activeTripIds: Flow<Long?>,
    private val eligibility: SyncEligibility,
    private val trigger: suspend () -> Boolean,
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        recorder: TripRecorder,
        eligibility: SyncEligibility,
        scheduler: SyncScheduler,
    ) : this(scope, recorder.active.map { it?.id }, eligibility, scheduler::syncAfterTrip)

    fun install() {
        scope.launch {
            var previous: Long? = null
            activeTripIds.distinctUntilChanged().collect { id ->
                val ended = previous
                previous = id
                if (ended != null && id != ended) onTripEnded()
            }
        }
    }

    internal suspend fun onTripEnded() {
        if (!eligibility.isOn()) return
        val queued = runCatching { trigger() }
            .onFailure { OrangeLog.w(OrangeLog.Areas.BACKEND, "trip_end_sync_failed", "error" to it.message) }
            .getOrDefault(false)
        OrangeLog.i(OrangeLog.Areas.BACKEND, "trip_end_sync", "queued" to queued)
    }
}
