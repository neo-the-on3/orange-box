package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.EventDto
import io.github.neotheon3.orangebox.core.network.SampleDto
import io.github.neotheon3.orangebox.core.network.TelemetryBatchDto
import io.github.neotheon3.orangebox.core.network.TripDto
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.network.VehicleDto
import java.time.Instant

/** A local vehicle as the sync sees it. [serverId] is null until `POST /v1/vehicles` succeeded. */
data class SyncVehicle(
    val clientVehicleId: String,
    val serverId: String?,
    val create: VehicleCreateDto,
    /** `410 vehicle_deleted` outside recovery: deleted on another device. Its trips aren't uploaded. */
    val goneOnServer: Boolean = false,
)

/** A local trip as the sync sees it. */
data class SyncTrip(
    val clientTripId: String,
    val serverId: String?,
    val clientVehicleId: String?,
    /** The server's `updatedAt` of the last version applied or acknowledged. */
    val serverUpdatedAt: Instant?,
    /** True once any upload of this trip was accepted (`created`/`updated`/`unchanged`). */
    val uploadedBefore: Boolean,
    /** True while the local snapshot is newer than what the server accepted (or it was never uploaded). */
    val needsUpload: Boolean,
    /** Pulled from the server (another device), not recorded here. */
    val remoteOrigin: Boolean = false,
    /** `snapshotAt` of our last accepted upload, if any. */
    val acceptedSnapshotAt: Instant? = null,
    /** The trip has ended on this device. */
    val completedLocally: Boolean = false,
)

/** A trip upload request kept until it succeeds, so retries resend the same body with the same key. */
data class PendingTripBatch(val idempotencyKey: String, val trips: List<TripUploadDto>)

/** A trip with telemetry still to upload: finished, recorded here, its vehicle known to the server. */
data class TelemetryTrip(val clientTripId: String, val vehicleServerId: String)

/**
 * The next samples and events of a trip after the last acknowledged ones. [samplesUpTo] / [eventsUpTo] are
 * opaque local cursors (row ids in Room) stored with [SyncStore.markTelemetrySent] once the server acknowledged.
 */
data class TelemetryChunk(val samples: List<SampleDto>, val events: List<EventDto>, val samplesUpTo: Long, val eventsUpTo: Long)

/** A telemetry upload kept until it's acknowledged, so retries resend the same body, `clientBatchId` and key. */
data class PendingTelemetryBatch(val idempotencyKey: String, val batch: TelemetryBatchDto, val samplesUpTo: Long, val eventsUpTo: Long)

/**
 * The local side of the sync (Room in the app, [InMemorySyncStore] in tests). Implementations make each call
 * atomic; the engine orders them so a crash at any point is safe to resume (delivery is at least once).
 */
interface SyncStore {
    /** The stored sync token (opaque `nextSince`), or null before the first full walk. */
    suspend fun since(): String?

    suspend fun storeSince(token: String)

    suspend fun clearSince()

    suspend fun vehicles(): List<SyncVehicle>

    suspend fun setVehicleServerId(clientVehicleId: String, serverId: String?)

    suspend fun markVehicleGone(clientVehicleId: String)

    /**
     * The server's copy of a local vehicle. The first copy seen only records its `updatedAt`; a later, newer one
     * is an edit from another device and is applied locally. Returns true when applied.
     */
    suspend fun applyRemoteVehicle(clientVehicleId: String, remote: VehicleDto): Boolean

    /** `410` during recovery: delete the vehicle and its local trips except [keepClientTripIds]. */
    suspend fun deleteVehicle(clientVehicleId: String, keepClientTripIds: Set<String>)

    suspend fun trips(): List<SyncTrip>

    /** Upserts a pulled trip. Called only when it's new or its `updatedAt` is newer than the stored one. */
    suspend fun applyRemoteTrip(trip: TripDto)

    /** Deletes the trip locally (deleted elsewhere, or `trip_deleted` on upload). No local tombstone is kept. */
    suspend fun deleteTripLocally(clientTripId: String)

    /** Trips the user deleted on this device: never resurrected by a pull, never re-uploaded. */
    suspend fun localDeletions(): List<LocalDeletion>

    suspend fun markLocalDeletionPushed(clientTripId: String)

    /** Builds the upload body of a trip, or null if it can't be sent yet (e.g. its vehicle has no server id). */
    suspend fun uploadOf(clientTripId: String): TripUploadDto?

    suspend fun markUploaded(clientTripId: String, serverId: String, snapshotAt: Instant)

    /** `rejected` for a reason that retrying won't fix; kept locally, not retried until it changes. */
    suspend fun markRejected(clientTripId: String, code: String?)

    /** Makes the trips upload again (recovery after `deployment_changed`). */
    suspend fun requeueUpload(clientTripIds: Set<String>)

    suspend fun pendingTripBatch(): PendingTripBatch?

    suspend fun setPendingTripBatch(batch: PendingTripBatch?)

    // --- telemetry (#67) ---

    /**
     * Trips whose telemetry isn't fully uploaded, oldest first: finished, recorded on this device, summary
     * uploaded before, vehicle with a server id, not marked done, and not older than the backlog bound.
     */
    suspend fun telemetryTrips(): List<TelemetryTrip>

    /** Up to [maxSamples] samples and [maxEvents] events after the acknowledged cursors, or null when nothing is left. */
    suspend fun telemetryChunk(clientTripId: String, maxSamples: Int, maxEvents: Int): TelemetryChunk?

    suspend fun markTelemetrySent(clientTripId: String, samplesUpTo: Long, eventsUpTo: Long)

    /** Stops uploading this trip's telemetry: `trip_deleted`, or a rejection retrying won't fix (`422`, unsplittable `413`). */
    suspend fun markTelemetryDone(clientTripId: String, code: String?)

    suspend fun pendingTelemetryBatch(): PendingTelemetryBatch?

    suspend fun setPendingTelemetryBatch(batch: PendingTelemetryBatch?)

    /** Epoch millis before which the [step] (see [SyncEngine.STEP_TELEMETRY]) isn't tried, or null. */
    suspend fun pausedUntil(step: String): Long?

    suspend fun pauseUntil(step: String, atMillis: Long?)

    /** DTC reads and clears waiting for upload, oldest first (#67). */
    suspend fun dtcReports(): List<QueuedDtcReport>

    /** Uploaded (or rejected, or its vehicle is gone): forget it. */
    suspend fun removeDtcReport(clientReportId: String)
}

/** A DTC read or clear waiting for upload, for the vehicle with [clientVehicleId]. */
data class QueuedDtcReport(val clientVehicleId: String, val report: DtcReportCreateDto)

data class LocalDeletion(val clientTripId: String, val serverId: String?, val pushed: Boolean)
