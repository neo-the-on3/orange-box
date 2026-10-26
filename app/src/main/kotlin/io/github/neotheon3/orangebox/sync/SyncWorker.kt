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
class SyncRunner @Inject constructor(
    private val backendConfig: BackendConfigRepository,
    private val clients: BackendClients,
    private val store: RoomSyncStore,
) {
    internal var storeOverride: SyncStore? = null

    suspend fun run(attempt: Int): SyncOutcome {
        val config = backendConfig.config.first()
        val api = clients.forConfig(config) ?: return SyncOutcome.Done
        if (config.mode == BackendMode.REMOTE && clients.sessionStore.read() == null) return SyncOutcome.Done
        return try {
            val r = SyncEngine(api, storeOverride ?: store).run()
            OrangeLog.i(
                OrangeLog.Areas.BACKEND, "sync_ok", "mode" to config.mode.wireName, "pulled" to r.pulled, "uploaded" to r.uploaded,
                "deleted" to r.deletedByServer, "rejected" to r.rejected, "recovery" to (r.recovery?.name ?: "none"), "dropped" to r.dropped,
            )
            SyncOutcome.Done
        } catch (e: ApiError) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to e.isRetryable)
            val retryAfter = (e as? ApiError.Http)?.retryAfterSeconds
            when {
                !e.isRetryable -> SyncOutcome.Failed(e.message ?: "error")
                retryAfter != null -> SyncOutcome.RetryAfter(retryAfter)
                attempt >= MAX_ATTEMPTS -> SyncOutcome.Failed("gave up after $attempt attempts")
                else -> SyncOutcome.Retry
            }
        } catch (e: IllegalStateException) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to false)
            SyncOutcome.Failed(e.message ?: "error")
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
