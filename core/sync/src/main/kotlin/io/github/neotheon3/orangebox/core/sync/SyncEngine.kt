package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.SyncWindowReason
import io.github.neotheon3.orangebox.core.network.TelemetryBatchDto
import io.github.neotheon3.orangebox.core.network.TripBatchDto
import io.github.neotheon3.orangebox.core.network.TripDto
import java.util.UUID

/**
 * One sync run (M9), following docs/backend/openapi-v2.yaml → Conventions → Incremental sync:
 *
 * 1. push local deletions (`DELETE /v1/trips/{id}`);
 * 2. pull: without a stored token a full `GET /v1/trips` walk; with token S, walk `GET /v1/trips?since=S` to its
 *    last page (S' = its `nextSince`), then `GET /v1/trips/deletions?since=S`, then store S';
 * 3. push vehicles without a server id (`POST /v1/vehicles`, de-duplicated by `clientVehicleId`), then trip
 *    snapshots (`POST /v1/trips/batch`);
 * 4. push telemetry of finished, uploaded trips (`POST /v1/telemetry/batches`, at most 5,000 samples and 500 events
 *    per batch, oldest trip first). A batch is stored before it is sent and resent unchanged (same `clientBatchId`
 *    and `Idempotency-Key`) until acknowledged; `413` halves the batch, `422` stops that trip's telemetry,
 *    `404 vehicle_not_found` re-creates the vehicle next run, `trip_deleted` drops the rest.
 * 5. push queued DTC reads and clears (`POST /v1/vehicles/{vehicleId}/dtc-reports`, oldest first). The
 *    `clientReportId` is the `Idempotency-Key`, so a resend after a lost response is a `200` replay. Reports wait
 *    for their vehicle's server id; `404` re-creates the vehicle next run; `400`, `413` and `422` drop the report.
 *
 * Telemetry and DTC reports aren't implemented on the server yet (`501`): that step stops, keeps everything queued
 * (the pending batch with its key, every report) and is skipped for [NOT_IMPLEMENTED_RETRY_MILLIS]; a `501`
 * never drops data and doesn't fail the run.
 *
 * `400 sync_window_expired` → a full walk, then by `reason`: `deployment_changed` re-creates vehicles the server
 * lost and re-uploads trips it didn't return (fresh `Idempotency-Key`s); `purged`, `invalid` and anything else
 * drop the uploaded-before trips it didn't return and re-upload nothing. Expiry on a cursor restarts the walk.
 * Retryable failures (`503`, `429`, network) propagate as [ApiError] for the worker to back off.
 */
class SyncEngine(
    private val api: BackendApi,
    private val store: SyncStore,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val maxRestarts: Int = 3,
    private val maxTelemetryBatchesPerRun: Int = MAX_TELEMETRY_BATCHES_PER_RUN,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Report(
        val pulled: Int = 0,
        val deletedByServer: Int = 0,
        val uploaded: Int = 0,
        val rejected: Int = 0,
        val recovery: SyncWindowReason? = null,
        val dropped: Int = 0,
        val vehiclesCreated: Int = 0,
        val telemetryBatches: Int = 0,
        val telemetrySamples: Int = 0,
        val telemetryEvents: Int = 0,
        /** Trips whose telemetry was rejected (`422`, or `413` even at one item). */
        val telemetryRejected: Int = 0,
        val dtcReports: Int = 0,
        /** DTC reports the server refused (`400`, `413`, `422`) or whose vehicle is gone; not retried. */
        val dtcReportsDropped: Int = 0,
        /** Steps the server answered `501` to (or still paused after one): nothing dropped, retried later. */
        val notImplemented: Set<String> = emptySet(),
    )

    private var report = Report()

    suspend fun run(): Report {
        report = Report()
        pushDeletions()
        pull()
        pushVehicles()
        pushTrips()
        pushTelemetry()
        pushDtcReports()
        return report
    }

    // --- pull ---

    private suspend fun pull() {
        val since = store.since()
        if (since == null) {
            val walk = fullWalk()
            store.storeSince(walk.nextSince)
            return
        }
        val reason = try {
            val next = walkTrips(since)
            walkDeletions(since)
            store.storeSince(next)
            return
        } catch (e: ApiError.Http) {
            if (!e.isSyncWindowExpired) throw e
            e.syncWindowReason
        }
        recover(reason)
    }

    private class FullWalk(val returned: Set<String>, val nextSince: String)

    /** Full `GET /v1/trips` without `since`. Expiry on a cursor discards the walk and starts it over. */
    private suspend fun fullWalk(): FullWalk {
        repeat(maxRestarts + 1) {
            val returned = mutableSetOf<String>()
            try {
                var cursor: String? = null
                while (true) {
                    val page = api.listTrips(since = null, cursor = cursor)
                    page.items.forEach { apply(it); returned += it.clientTripId }
                    cursor = page.nextCursor ?: return FullWalk(returned, page.nextSince ?: error("last page without nextSince"))
                }
            } catch (e: ApiError.Http) {
                if (!e.isSyncWindowExpired) throw e
            }
        }
        throw IllegalStateException("full walk kept expiring")
    }

    /** Incremental walk; returns S'. */
    private suspend fun walkTrips(since: String): String {
        var page = api.listTrips(since = since)
        while (true) {
            page.items.forEach { apply(it) }
            val cursor = page.nextCursor ?: return page.nextSince ?: error("last page without nextSince")
            page = api.listTrips(cursor = cursor)
        }
    }

    private suspend fun walkDeletions(since: String) {
        val local = store.trips().associateBy { it.clientTripId }
        var cursor: String? = null
        do {
            val page = api.listTripDeletions(since, cursor)
            page.items.forEach { d ->
                if (d.clientTripId in local) {
                    store.deleteTripLocally(d.clientTripId)
                    report = report.copy(deletedByServer = report.deletedByServer + 1)
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }

    /** Upsert by server id (or our own `clientTripId`); the newer `updatedAt` wins; local deletions win. */
    private suspend fun apply(remote: TripDto) {
        if (store.localDeletions().any { it.clientTripId == remote.clientTripId }) return
        val local = store.trips().firstOrNull { it.serverId == remote.id || it.clientTripId == remote.clientTripId }
        if (local?.serverUpdatedAt != null && !remote.updatedAt.isAfter(local.serverUpdatedAt)) return
        store.applyRemoteTrip(remote)
        report = report.copy(pulled = report.pulled + 1)
    }

    private suspend fun recover(first: SyncWindowReason) {
        var reason = first
        repeat(maxRestarts + 1) {
            report = report.copy(recovery = reason)
            try {
                val walk = fullWalk()
                if (reason == SyncWindowReason.DEPLOYMENT_CHANGED) recoverDeployment(walk) else dropUnreturned(walk.returned)
                store.storeSince(walk.nextSince)
                return
            } catch (e: ApiError.Http) {
                if (!e.isSyncWindowExpired) throw e
                // deployment_changed wins over the others; another one during recovery starts it over.
                if (e.syncWindowReason == SyncWindowReason.DEPLOYMENT_CHANGED) reason = SyncWindowReason.DEPLOYMENT_CHANGED
            }
        }
        throw IllegalStateException("sync recovery kept expiring")
    }

    /** `purged` / `invalid` / unknown: drop the uploaded-before trips the server didn't return. Never re-upload. */
    private suspend fun dropUnreturned(returned: Set<String>) {
        store.trips().filter { it.uploadedBefore && it.clientTripId !in returned }.forEach {
            store.deleteTripLocally(it.clientTripId)
            report = report.copy(dropped = report.dropped + 1)
        }
    }

    /** `deployment_changed`: re-create lost vehicles (remap), then re-upload the trips the server lost. */
    private suspend fun recoverDeployment(walk: FullWalk) {
        store.setPendingTripBatch(null) // re-uploads get fresh Idempotency-Keys
        val serverVehicles = buildSet {
            var cursor: String? = null
            do {
                val page = api.listVehicles(cursor)
                page.items.forEach { v -> v.clientVehicleId?.let { add(it to v.id) } }
                cursor = page.nextCursor
            } while (cursor != null)
        }.toMap()
        for (v in store.vehicles()) {
            val known = serverVehicles[v.clientVehicleId]
            if (known != null) {
                if (known != v.serverId) store.setVehicleServerId(v.clientVehicleId, known)
                continue
            }
            if (v.serverId == null && store.trips().none { it.clientVehicleId == v.clientVehicleId }) continue
            try {
                val created = api.createVehicle(v.create, newKey())
                store.setVehicleServerId(v.clientVehicleId, created.id)
                report = report.copy(vehiclesCreated = report.vehiclesCreated + 1)
            } catch (e: ApiError.Http) {
                if (e.code != ApiError.CODE_VEHICLE_DELETED) throw e
                store.deleteVehicle(v.clientVehicleId, keepClientTripIds = walk.returned)
            }
        }
        val deletedHere = store.localDeletions().map { it.clientTripId }.toSet()
        val lost = store.trips()
            .filter { !it.remoteOrigin && it.clientTripId !in walk.returned && it.clientTripId !in deletedHere }
            .map { it.clientTripId }.toSet()
        store.requeueUpload(lost)
    }

    // --- push ---

    private suspend fun pushDeletions() {
        for (d in store.localDeletions().filter { !it.pushed }) {
            d.serverId?.let { api.deleteTrip(it) }
            store.markLocalDeletionPushed(d.clientTripId)
        }
    }

    private suspend fun pushVehicles() {
        val withTrips = store.trips().filter { it.needsUpload }.mapNotNull { it.clientVehicleId }.toSet() +
            store.dtcReports().map { it.clientVehicleId }
        for (v in store.vehicles().filter { it.serverId == null && !it.goneOnServer && it.clientVehicleId in withTrips }) {
            try {
                // A fresh key is fine: the server de-duplicates by clientVehicleId (200 replay).
                val created = api.createVehicle(v.create, newKey())
                store.setVehicleServerId(v.clientVehicleId, created.id)
                report = report.copy(vehiclesCreated = report.vehiclesCreated + 1)
            } catch (e: ApiError.Http) {
                if (e.code != ApiError.CODE_VEHICLE_DELETED) throw e
                store.markVehicleGone(v.clientVehicleId) // deleted on another device: don't retry
            }
        }
    }

    private suspend fun pushTrips() {
        val attempted = mutableSetOf<String>() // each trip at most once per run, whatever the server answers
        while (true) {
            val batch = store.pendingTripBatch() ?: nextBatch(attempted) ?: return
            attempted += batch.trips.map { it.clientTripId }
            val result = api.uploadTrips(TripBatchDto(batch.trips), batch.idempotencyKey)
            val sent = batch.trips.associateBy { it.clientTripId }
            for (item in result.results) {
                val trip = sent[item.clientId] ?: continue
                when {
                    item.accepted && item.id != null -> {
                        store.markUploaded(item.clientId, item.id!!, trip.snapshotAt)
                        report = report.copy(uploaded = report.uploaded + 1)
                    }
                    item.problem?.code == ApiError.CODE_TRIP_DELETED -> store.deleteTripLocally(item.clientId)
                    item.problem?.code == ApiError.CODE_VEHICLE_NOT_FOUND -> {
                        // The server lost or never had the vehicle: re-create it next run.
                        store.vehicles().firstOrNull { it.serverId == trip.vehicleId }?.let { store.setVehicleServerId(it.clientVehicleId, null) }
                    }
                    else -> {
                        store.markRejected(item.clientId, item.problem?.code)
                        report = report.copy(rejected = report.rejected + 1)
                    }
                }
            }
            store.setPendingTripBatch(null)
        }
    }

    private suspend fun nextBatch(attempted: Set<String>): PendingTripBatch? {
        val gone = store.vehicles().filter { it.goneOnServer }.map { it.clientVehicleId }.toSet()
        val uploads = store.trips()
            .filter { it.needsUpload && !it.remoteOrigin && it.clientVehicleId !in gone && it.clientTripId !in attempted }
            .mapNotNull { store.uploadOf(it.clientTripId) }
            .take(BackendApi.MAX_TRIPS_PER_BATCH)
        if (uploads.isEmpty()) return null
        return PendingTripBatch(newKey(), uploads).also { store.setPendingTripBatch(it) }
    }

    // --- telemetry (#67) ---

    private enum class Sent { OK, TOO_LARGE, TRIP_DONE, VEHICLE_MISSING, NOT_IMPLEMENTED }

    /** Batch size for this run; `413` halves it for the rest of the run. */
    private var maxSamples = BackendApi.MAX_SAMPLES_PER_BATCH
    private var maxEvents = BackendApi.MAX_EVENTS_PER_BATCH

    /** True while [step] waits out an earlier `501`. */
    private suspend fun paused(step: String): Boolean {
        val until = store.pausedUntil(step) ?: return false
        if (clock() >= until) {
            store.pauseUntil(step, null)
            return false
        }
        report = report.copy(notImplemented = report.notImplemented + step)
        return true
    }

    private suspend fun pauseNotImplemented(step: String) {
        store.pauseUntil(step, clock() + NOT_IMPLEMENTED_RETRY_MILLIS)
        report = report.copy(notImplemented = report.notImplemented + step)
    }

    private suspend fun pushTelemetry() {
        if (paused(STEP_TELEMETRY)) return
        maxSamples = BackendApi.MAX_SAMPLES_PER_BATCH
        maxEvents = BackendApi.MAX_EVENTS_PER_BATCH
        store.pendingTelemetryBatch()?.let { pending ->
            when (send(pending)) {
                Sent.VEHICLE_MISSING, Sent.NOT_IMPLEMENTED -> return
                Sent.TOO_LARGE -> shrink(pending.batch.samples.size, pending.batch.events.size)
                else -> Unit
            }
        }
        for (trip in store.telemetryTrips()) {
            if (!uploadTrip(trip.clientTripId, trip.vehicleServerId)) return
        }
    }

    private fun shrink(samples: Int, events: Int) {
        maxSamples = maxOf(1, minOf(maxSamples, samples) / 2)
        maxEvents = maxOf(1, minOf(maxEvents, events) / 2)
    }

    /** Uploads a trip's remaining telemetry in batches. Returns false when this run should stop uploading telemetry. */
    private suspend fun uploadTrip(clientTripId: String, vehicleId: String): Boolean {
        while (true) {
            if (report.telemetryBatches >= maxTelemetryBatchesPerRun) return false
            val chunk = store.telemetryChunk(clientTripId, maxSamples, maxEvents) ?: return true
            val pending = PendingTelemetryBatch(
                idempotencyKey = newKey(),
                batch = TelemetryBatchDto(newKey(), clientTripId, vehicleId, chunk.samples, chunk.events),
                samplesUpTo = chunk.samplesUpTo,
                eventsUpTo = chunk.eventsUpTo,
            )
            store.setPendingTelemetryBatch(pending)
            when (send(pending)) {
                Sent.OK -> Unit
                Sent.TRIP_DONE -> return true
                Sent.VEHICLE_MISSING, Sent.NOT_IMPLEMENTED -> return false
                Sent.TOO_LARGE -> {
                    if (chunk.samples.size + chunk.events.size <= 1) {
                        store.markTelemetryDone(clientTripId, ApiError.CODE_BATCH_TOO_LARGE)
                        report = report.copy(telemetryRejected = report.telemetryRejected + 1)
                        return true
                    }
                    shrink(chunk.samples.size, chunk.events.size)
                }
            }
        }
    }

    private suspend fun send(p: PendingTelemetryBatch): Sent {
        val clientTripId = p.batch.clientTripId
        try {
            val ack = api.uploadTelemetry(p.batch, p.idempotencyKey)
            if (ack.tripDeleted) store.markTelemetryDone(clientTripId, ack.status)
            else store.markTelemetrySent(clientTripId, p.samplesUpTo, p.eventsUpTo)
            store.setPendingTelemetryBatch(null)
            report = report.copy(
                telemetryBatches = report.telemetryBatches + 1,
                telemetrySamples = report.telemetrySamples + ack.acceptedSamples,
                telemetryEvents = report.telemetryEvents + ack.acceptedEvents,
            )
            return if (ack.tripDeleted) Sent.TRIP_DONE else Sent.OK
        } catch (e: ApiError.Http) {
            when {
                e.status == 501 -> {
                    pauseNotImplemented(STEP_TELEMETRY) // the pending batch stays, with its clientBatchId and key
                    return Sent.NOT_IMPLEMENTED
                }
                e.status == 413 -> {
                    store.setPendingTelemetryBatch(null) // the halves are new bodies: new clientBatchId and key
                    return Sent.TOO_LARGE
                }
                e.status == 404 && e.code == ApiError.CODE_VEHICLE_NOT_FOUND -> {
                    store.setPendingTelemetryBatch(null)
                    store.vehicles().firstOrNull { it.serverId == p.batch.vehicleId }?.let { store.setVehicleServerId(it.clientVehicleId, null) }
                    return Sent.VEHICLE_MISSING
                }
                e.status == 422 -> {
                    store.setPendingTelemetryBatch(null)
                    store.markTelemetryDone(clientTripId, e.code ?: "unprocessable")
                    report = report.copy(telemetryRejected = report.telemetryRejected + 1)
                    return Sent.TRIP_DONE
                }
                else -> throw e // retryable or not, the pending batch stays and is resent with the same key
            }
        }
    }

    // --- DTC reports ---

    private suspend fun pushDtcReports() {
        if (paused(STEP_DTC_REPORTS)) return
        val vehicles = store.vehicles().associateBy { it.clientVehicleId }
        val missing = mutableSetOf<String>() // 404 this run: wait for the vehicle to be re-created
        for (q in store.dtcReports()) {
            val id = q.report.clientReportId
            val v = vehicles[q.clientVehicleId]
            if (v == null || v.goneOnServer) {
                store.removeDtcReport(id)
                report = report.copy(dtcReportsDropped = report.dtcReportsDropped + 1)
                continue
            }
            val serverId = v.serverId ?: continue
            if (q.clientVehicleId in missing) continue
            try {
                api.createDtcReport(serverId, q.report, idempotencyKey = id)
                store.removeDtcReport(id)
                report = report.copy(dtcReports = report.dtcReports + 1)
            } catch (e: ApiError.Http) {
                when {
                    e.status == 501 -> {
                        pauseNotImplemented(STEP_DTC_REPORTS) // every report stays queued
                        return
                    }
                    e.status == 404 -> {
                        store.setVehicleServerId(q.clientVehicleId, null)
                        missing += q.clientVehicleId
                    }
                    e.status == 400 || e.status == 413 || e.status == 422 -> {
                        store.removeDtcReport(id)
                        report = report.copy(dtcReportsDropped = report.dtcReportsDropped + 1)
                    }
                    else -> throw e // 401, 409 in flight, 429, 503: retried with the same key
                }
            }
        }
    }

    companion object {
        /** Keeps one run short; the rest goes out on the next run. */
        const val MAX_TELEMETRY_BATCHES_PER_RUN = 50

        const val STEP_TELEMETRY = "telemetry"
        const val STEP_DTC_REPORTS = "dtc_reports"

        /** After a `501`, the step waits this long before trying again (the server may ship it any day). */
        const val NOT_IMPLEMENTED_RETRY_MILLIS = 24L * 60 * 60 * 1000
    }
}
