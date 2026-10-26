package io.github.neotheon3.orangebox.core.network

/**
 * The REST v1 operations the app uses. [OkHttpBackendApi] talks to a server; Fake mode uses an in-process
 * implementation with the same semantics. Every call throws [ApiError] on failure.
 */
interface BackendApi {
    suspend fun health(): HealthDto

    suspend fun requestOtp(email: String): OtpResponse

    suspend fun signIn(email: String, code: String, device: DeviceInfo? = null): TokenResponse

    suspend fun logout()

    suspend fun listVehicles(cursor: String? = null): VehiclePage

    /** `POST /v1/vehicles`: `201` created or `200` replay by `clientVehicleId`. `410 vehicle_deleted` is thrown. */
    suspend fun createVehicle(vehicle: VehicleCreateDto, idempotencyKey: String): VehicleDto

    /** `GET /v1/trips`. Pass [since] only on the first page of an incremental walk; later pages need only [cursor]. */
    suspend fun listTrips(since: String? = null, cursor: String? = null): TripPage

    /** `GET /v1/trips/deletions?since=…`. */
    suspend fun listTripDeletions(since: String, cursor: String? = null): TripDeletionPage

    /** `POST /v1/trips/batch` (1–100 trips). */
    suspend fun uploadTrips(batch: TripBatchDto, idempotencyKey: String): BatchResultDto

    /** `DELETE /v1/trips/{id}`. A `404` (already deleted) counts as success. */
    suspend fun deleteTrip(serverId: String)

    companion object {
        const val MAX_TRIPS_PER_BATCH = 100
    }
}
