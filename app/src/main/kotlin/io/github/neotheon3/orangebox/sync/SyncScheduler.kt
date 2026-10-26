package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
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

    fun cancelAll() {
        work.cancelUniqueWork(ONE_OFF)
        work.cancelUniqueWork(PERIODIC)
    }

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val ONE_OFF = "backend_sync_now"
        const val PERIODIC = "backend_sync_periodic"
        const val BACKOFF_SECONDS = 30L
    }
}
