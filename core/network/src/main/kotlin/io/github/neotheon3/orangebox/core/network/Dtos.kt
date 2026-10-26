package io.github.neotheon3.orangebox.core.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.time.Instant

/*
 * Wire types for the parts of docs/backend/openapi-v2.yaml the app uses. Field names match the spec.
 * Unknown fields are ignored ([BackendJson]); open string sets (fuel type, status, reason) are kept as
 * strings so a newer server can't break decoding.
 */

typealias UtcInstant = @Serializable(with = UtcInstantSerializer::class) Instant

/** Shared JSON settings: lenient on input (unknown keys, unknown enum strings), no nulls for absent fields. */
@OptIn(ExperimentalSerializationApi::class)
val BackendJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

@Serializable data class HealthDto(val status: String, val apiVersion: String, val time: UtcInstant)

// --- Auth (email + OTP) ---

@Serializable data class OtpRequest(val email: String)

@Serializable data class OtpResponse(val expiresIn: Int, val resendAfter: Int? = null)

@Serializable data class DeviceInfo(val appVersion: String? = null, val osVersion: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class EmailOtpGrant(
    val email: String,
    val code: String,
    val device: DeviceInfo? = null,
    @EncodeDefault val grantType: String = "email_otp",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class RefreshGrant(
    val refreshToken: String,
    @EncodeDefault val grantType: String = "refresh_token",
)

@Serializable data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshToken: String,
    val refreshExpiresIn: Long,
    val memberId: String,
    val isNewMember: Boolean,
)

// --- Vehicles ---

@Serializable data class VehicleCreateDto(
    val clientVehicleId: String,
    val make: String,
    val model: String,
    /** Spec `FuelType`: gasoline, diesel, lpg, gasoline_lpg, hybrid, plug_in_hybrid, electric, other. */
    val fuelType: String,
    val nickname: String? = null,
    val engineDisplacementCc: Int? = null,
    val odometerKm: Int? = null,
)

@Serializable data class VehicleDto(
    val id: String,
    val make: String,
    val model: String,
    val fuelType: String,
    val archived: Boolean = false,
    val createdAt: UtcInstant,
    val updatedAt: UtcInstant,
    val clientVehicleId: String? = null,
    val nickname: String? = null,
    val engineDisplacementCc: Int? = null,
    val odometerKm: Int? = null,
)

@Serializable data class VehiclePage(val items: List<VehicleDto>, val nextCursor: String? = null)

// --- Trips ---

@Serializable data class AppInfo(val version: String? = null, val os: String? = null, val osVersion: String? = null)

@Serializable data class TripUploadDto(
    val clientTripId: String,
    /** Server vehicle id. */
    val vehicleId: String,
    /** in_progress or completed. */
    val state: String,
    val startedAt: UtcInstant,
    val snapshotAt: UtcInstant,
    val endedAt: UtcInstant? = null,
    val durationS: Long? = null,
    val distanceKm: Double? = null,
    val maxSpeedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val harshAccelCount: Int? = null,
    val harshBrakeCount: Int? = null,
    val fuelUsedL: Double? = null,
    val fuelEconomyKmPerL: Double? = null,
    val fuelCutTimeS: Double? = null,
    val idleTimeS: Double? = null,
    val ecoSpeedTimeS: Double? = null,
    val overSpeedTimeS: Double? = null,
    val simulated: Boolean? = null,
    val app: AppInfo? = null,
) {
    companion object {
        const val STATE_IN_PROGRESS = "in_progress"
        const val STATE_COMPLETED = "completed"
    }
}

/** Spec `Trip`: a [TripUploadDto] plus the server's `id`, `createdAt` and `updatedAt`. */
@Serializable data class TripDto(
    val id: String,
    val clientTripId: String,
    val vehicleId: String,
    val state: String,
    val startedAt: UtcInstant,
    val snapshotAt: UtcInstant,
    val createdAt: UtcInstant,
    val updatedAt: UtcInstant,
    val endedAt: UtcInstant? = null,
    val durationS: Long? = null,
    val distanceKm: Double? = null,
    val maxSpeedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val harshAccelCount: Int? = null,
    val harshBrakeCount: Int? = null,
    val fuelUsedL: Double? = null,
    val fuelCutTimeS: Double? = null,
    val idleTimeS: Double? = null,
    val ecoSpeedTimeS: Double? = null,
    val overSpeedTimeS: Double? = null,
    val simulated: Boolean? = null,
)

@Serializable data class TripBatchDto(val trips: List<TripUploadDto>)

@Serializable data class BatchItemResult(
    val clientId: String,
    /** created, updated, unchanged or rejected (kept as a string: unknown values are treated as rejected). */
    val status: String,
    val id: String? = null,
    val problem: ProblemDto? = null,
) {
    val accepted: Boolean get() = status == "created" || status == "updated" || status == "unchanged"
}

@Serializable data class BatchResultDto(val results: List<BatchItemResult>)

@Serializable data class TripPage(val items: List<TripDto>, val nextCursor: String? = null, val nextSince: String? = null)

@Serializable data class TripDeletionDto(
    val id: String,
    val clientTripId: String,
    val deletedAt: UtcInstant,
    val vehicleId: String? = null,
)

@Serializable data class TripDeletionPage(
    val items: List<TripDeletionDto>,
    val nextCursor: String? = null,
    val nextSince: String? = null,
)

// --- Errors ---

/** RFC 9457 problem details as the spec defines them (`Problem`). */
@Serializable data class ProblemDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
    val requestId: String? = null,
    /** Kept as raw JSON so a reason of an unexpected type can't make the whole problem undecodable. */
    @SerialName("reason") val rawReason: JsonElement? = null,
    val errors: List<FieldError>? = null,
) {
    val reason: String? get() = (rawReason as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Serializable data class FieldError(val pointer: String, val detail: String)
}

/**
 * `Problem.reason` on `sync_window_expired`. An open set (`x-extensible-enum`): unknown or missing values
 * decode to [UNKNOWN], which the app treats like [INVALID].
 */
enum class SyncWindowReason(val wire: String?) {
    DEPLOYMENT_CHANGED("deployment_changed"),
    PURGED("purged"),
    INVALID("invalid"),
    UNKNOWN(null),
    ;

    companion object {
        fun parse(value: String?): SyncWindowReason =
            entries.firstOrNull { it.wire != null && it.wire == value?.trim()?.lowercase() } ?: UNKNOWN
    }
}
