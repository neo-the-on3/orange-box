package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the sync work in line with the backend mode: Off cancels everything (and nothing is enqueued), Fake and
 * Remote keep a periodic sync (every 6 h, on any network) and run one soon after a change.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backendConfig: BackendConfigRepository,
) {
    private val work get() = WorkManager.getInstance(context)

    fun install(scope: CoroutineScope) {
        scope.launch {
            backendConfig.config.distinctUntilChanged().collect { config ->
                if (config.mode == BackendMode.OFF || (config.mode == BackendMode.REMOTE && !config.isRemoteReady)) {
                    // Off must not even start WorkManager; only cancel what an earlier Fake/Remote run scheduled.
                    if (WorkManager.isInitialized()) cancelAll()
                } else {
                    schedulePeriodic()
                    syncSoon()
                }
            }
        }
    }

    /** A one-off run (after sign-in, a trip end, "Sync now", or the server's `Retry-After`). */
    fun syncSoon(delaySeconds: Long = 0) {
        work.enqueueUniqueWork(
            ONE_OFF,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * A one-off run after a trip ends, unless that would cut a backoff short: a one-off that is already queued
     * (waiting for network, delayed by `Retry-After`, or backing off) or running is kept, and nothing is queued
     * while the periodic sync is backing off after a failure (its retry uploads the trip). Returns true when a
     * run was queued.
     */
    suspend fun syncAfterTrip(): Boolean {
        val oneOff = work.getWorkInfosForUniqueWorkFlow(ONE_OFF).first()
        val periodic = work.getWorkInfosForUniqueWorkFlow(PERIODIC).first()
        if (!shouldQueueAfterTrip(oneOff, periodic)) return false
        work.enqueueUniqueWork(
            ONE_OFF,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        return true
    }

    private fun schedulePeriodic() {
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * A one-off sync ("Sync now", after sign-in) is queued or running. Not for Off (it would start WorkManager);
     * false while WorkManager isn't initialised (e.g. a test application without the startup initializer).
     */
    val syncing: kotlinx.coroutines.flow.Flow<Boolean>
        get() = if (!WorkManager.isInitialized()) kotlinx.coroutines.flow.flowOf(false) else work.getWorkInfosForUniqueWorkFlow(ONE_OFF).map { infos ->
            infos.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
        }

    fun cancelAll() {
        work.cancelUniqueWork(ONE_OFF)
        work.cancelUniqueWork(PERIODIC)
    }

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val ONE_OFF = "backend_sync_now"
        const val PERIODIC = "backend_sync_periodic"
        const val BACKOFF_SECONDS = 30L

        /** See [syncAfterTrip]. */
        internal fun shouldQueueAfterTrip(oneOff: List<WorkInfo>, periodic: List<WorkInfo>): Boolean {
            val pending = oneOff.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
            val periodicBackingOff = periodic.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
            return !pending && !periodicBackingOff
        }
    }
}
