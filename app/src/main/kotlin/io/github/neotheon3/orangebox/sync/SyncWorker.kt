package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.RoomSyncStore
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.datastore.SyncStatus
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import io.github.neotheon3.orangebox.core.sync.SyncStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a sync run asks WorkManager to do next. */
sealed interface SyncOutcome {
    /** Done, or nothing to do (Off, Remote without a session). */
    data object Done : SyncOutcome

    /** Retry with WorkManager's exponential backoff. */
    data object Retry : SyncOutcome

    /** The server sent `Retry-After`: run again after that many seconds. */
    data class RetryAfter(val seconds: Long) : SyncOutcome

    /** Won't fix itself by retrying (other 4xx, signed out, repeated recovery). */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * One backend sync run ([SyncEngine]). Off: nothing, and no client is built. Remote without a session: nothing
 * (sign in first). Retryable failures (`503`, `429`, network, `409` in flight) retry; other errors end the run.
 */
class SyncRunner(
    private val backendConfig: BackendConfigRepository,
    private val clients: BackendClients,
    private val store: RoomSyncStore,
    private val status: SyncStatusStore?,
    private val clock: () -> Long,
) {
    @Inject constructor(backendConfig: BackendConfigRepository, clients: BackendClients, store: RoomSyncStore, status: SyncStatusStore) :
        this(backendConfig, clients, store, status, System::currentTimeMillis)

    /** Tests without the status table. */
    constructor(backendConfig: BackendConfigRepository, clients: BackendClients, store: RoomSyncStore) :
        this(backendConfig, clients, store, null, System::currentTimeMillis)

    internal var storeOverride: SyncStore? = null

    suspend fun run(attempt: Int): SyncOutcome {
        val config = backendConfig.config.first()
        val api = clients.forConfig(config) ?: return SyncOutcome.Done
        if (config.mode == BackendMode.REMOTE && clients.sessionStore.read() == null) return SyncOutcome.Done
        val outcome = runEngine(api, config.mode, attempt)
        status?.write(outcome.second)
        return outcome.first
    }

    private suspend fun runEngine(api: io.github.neotheon3.orangebox.core.network.BackendApi, mode: BackendMode, attempt: Int): Pair<SyncOutcome, SyncStatus> {
        val now = clock()
        return try {
            val r = SyncEngine(api, storeOverride ?: store).run()
            OrangeLog.i(
                OrangeLog.Areas.BACKEND, "sync_ok", "mode" to mode.wireName, "pulled" to r.pulled, "uploaded" to r.uploaded,
                "deleted" to r.deletedByServer, "rejected" to r.rejected, "recovery" to (r.recovery?.name ?: "none"), "dropped" to r.dropped,
                "telemetry_batches" to r.telemetryBatches, "telemetry_rejected" to r.telemetryRejected,
            )
            SyncOutcome.Done to SyncStatus(now, ok = true, uploadedTrips = r.uploaded, pulledTrips = r.pulled, telemetryBatches = r.telemetryBatches)
        } catch (e: ApiError) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to e.isRetryable)
            val retryAfter = (e as? ApiError.Http)?.retryAfterSeconds
            val outcome = when {
                !e.isRetryable -> SyncOutcome.Failed(e.message ?: "error")
                retryAfter != null -> SyncOutcome.RetryAfter(retryAfter)
                attempt >= MAX_ATTEMPTS -> SyncOutcome.Failed("gave up after $attempt attempts")
                else -> SyncOutcome.Retry
            }
            outcome to SyncStatus(now, ok = false, error = errorOf(e), willRetry = outcome !is SyncOutcome.Failed)
        } catch (e: IllegalStateException) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to false)
            SyncOutcome.Failed(e.message ?: "error") to SyncStatus(now, ok = false, error = SyncError.RECOVERY)
        }
    }

    private fun errorOf(e: ApiError): SyncError = when (e) {
        is ApiError.Network -> SyncError.NETWORK
        is ApiError.NotSignedIn -> SyncError.SIGNED_OUT
        is ApiError.Http -> when {
            e.status == 429 || e.status >= 500 -> SyncError.SERVER_BUSY
            e.status == 401 -> SyncError.SIGNED_OUT
            else -> SyncError.REJECTED
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 10
    }
}

/** WorkManager wrapper around [SyncRunner]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun runner(): SyncRunner
        fun scheduler(): SyncScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return when (val outcome = deps.runner().run(runAttemptCount)) {
            SyncOutcome.Done -> Result.success()
            SyncOutcome.Retry -> Result.retry()
            is SyncOutcome.RetryAfter -> {
                deps.scheduler().syncSoon(delaySeconds = outcome.seconds)
                Result.success()
            }
            is SyncOutcome.Failed -> Result.failure()
        }
    }
}
