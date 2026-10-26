package io.github.neotheon3.orangebox.core.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject
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

/** Spec `VehicleCreate`. [clientVehicleId] is always sent (de-duplication per account). */
/**
 * Spec `VehicleCreate` (draft.9: only [fuelType] is required). [make] and [model] are left out when unknown,
 * never sent as `""`; the name goes in [nickname].
 */
@Serializable data class VehicleCreateDto(
    val clientVehicleId: String,
    val make: String? = null,
    val model: String? = null,
    /** Spec `FuelType` (strict on input: an unknown value is `422`); see [FuelTypes]. */
    val fuelType: String,
    val nickname: String? = null,
    val engineDisplacementCc: Int? = null,
    val odometerKm: Int? = null,
)

/**
 * Spec `Vehicle`. [make] and [model] are always present and may be null (draft.9); older rows may hold `""`,
 * read as null ([knownMake], [knownModel]). [fuelType] may be a value this app doesn't know yet.
 */
@Serializable data class VehicleDto(
    val id: String,
    val make: String?,
    val model: String?,
    val fuelType: String,
    val archived: Boolean = false,
    val createdAt: UtcInstant,
    val updatedAt: UtcInstant,
    val clientVehicleId: String? = null,
    val nickname: String? = null,
    val engineDisplacementCc: Int? = null,
    val odometerKm: Int? = null,
) {
    val knownMake: String? get() = make?.takeIf { it.isNotBlank() }
    val knownModel: String? get() = model?.takeIf { it.isNotBlank() }
}

/** Spec `VehiclePatch` (JSON merge patch): only the fields to change. */
@Serializable data class VehiclePatchDto(val fuelType: String? = null, val nickname: String? = null)

/** A response with its `ETag`. */
data class Tagged<T>(val value: T, val etag: String?)

/** Spec `FuelType` values (draft.9). Input is strict; responses may carry values not listed here. */
object FuelTypes {
    const val GASOLINE = "gasoline"
    const val DIESEL = "diesel"
    const val LPG = "lpg"
    const val GASOLINE_LPG = "gasoline_lpg"
    const val CNG = "cng"
    const val GASOLINE_CNG = "gasoline_cng"
    const val ETHANOL = "ethanol"
    const val FLEX = "flex"
    const val HYBRID = "hybrid"
    const val PLUG_IN_HYBRID = "plug_in_hybrid"
    const val ELECTRIC = "electric"
    const val OTHER = "other"
    val ALL = setOf(GASOLINE, DIESEL, LPG, GASOLINE_LPG, CNG, GASOLINE_CNG, ETHANOL, FLEX, HYBRID, PLUG_IN_HYBRID, ELECTRIC, OTHER)
}

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
    /** Overspeed episodes; sent in every snapshot (draft.9). */
    val overSpeedCount: Int? = null,
    /** Fuel-cut episodes (their time is [fuelCutTimeS]); sent in every snapshot (draft.9). */
    val fuelCutCount: Int? = null,
    /** Why it ended ([EndReasons]); only on `completed`, left out while `in_progress`. */
    val endReason: String? = null,
    val simulated: Boolean? = null,
    val app: AppInfo? = null,
) {
    companion object {
        const val STATE_IN_PROGRESS = "in_progress"
        const val STATE_COMPLETED = "completed"

        /** Server limits (`422` at `/trips/<i>` past them). */
        const val MAX_TRIP_BYTES = 16 * 1024
        const val MAX_VEHICLE_ID = 64
        const val MAX_APP_VERSION = 64
        const val MAX_APP_OS = 32
        const val MAX_APP_OS_VERSION = 128

        /** Not sent yet; the limits for when they are. */
        const val MAX_SPEED_BANDS = 32
        const val MAX_DTC_CODES_PER_LIST = 128
    }

    /** Cuts the free-text fields to the server's limits (the rest is bounded by construction). */
    fun withinLimits(): TripUploadDto = copy(
        app = app?.let {
            AppInfo(it.version?.take(MAX_APP_VERSION), it.os?.take(MAX_APP_OS), it.osVersion?.take(MAX_APP_OS_VERSION))
        },
    )

    /** The JSON size the server checks against [MAX_TRIP_BYTES]. */
    fun encodedBytes(): Int = BackendJson.encodeToString(serializer(), this).toByteArray().size
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
    val overSpeedCount: Int? = null,
    val fuelCutCount: Int? = null,
    /** Open set: read through [EndReasons.read], so an unknown value is `other`. */
    val endReason: String? = null,
    val simulated: Boolean? = null,
)

/** `TripUpload.endReason` (draft.9): an open set, like `Problem.reason`. */
object EndReasons {
    const val USER = "user"
    const val ENGINE_OFF = "engine_off"
    const val DISCONNECTED = "disconnected"
    const val PROCESS_DEATH = "process_death"
    const val OTHER = "other"
    val KNOWN = setOf(USER, ENGINE_OFF, DISCONNECTED, PROCESS_DEATH, OTHER)

    /** A received value: unknown ones are [OTHER]; null stays null (in progress). */
    fun read(wire: String?): String? = wire?.let { if (it in KNOWN) it else OTHER }
}

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
 * `Problem.reason` on `sync_window_expired`: an open string set (`x-extensible-enum`). The spec says to treat an
 * unknown or missing value like `invalid`, so both decode to [INVALID].
 */
enum class SyncWindowReason(val wire: String) {
    DEPLOYMENT_CHANGED("deployment_changed"),
    PURGED("purged"),
    INVALID("invalid"),
    ;

    companion object {
        fun parse(value: String?): SyncWindowReason = entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: INVALID
    }
}

// --- Telemetry (POST /v1/telemetry/batches) ---

@Serializable data class AccelDto(val x: Double, val y: Double, val z: Double)

/** `Sample`: one OBD/location reading. Only `t` is required; absent values are left out of the JSON. */
@Serializable data class SampleDto(
    val t: UtcInstant,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val mafGps: Double? = null,
    val mapKpa: Double? = null,
    val intakeAirC: Double? = null,
    val coolantC: Double? = null,
    val engineLoadPct: Double? = null,
    val throttlePct: Double? = null,
    val fuelLevelPct: Double? = null,
    val batteryV: Double? = null,
    val mil: Boolean? = null,
    val accel: AccelDto? = null,
    /** Extra mode 01 values keyed by hex PID (`"0x0B"`), already scaled. */
    val pids: Map<String, Double>? = null,
)

/** `Event`. [clientEventId] is assigned when the event is recorded and resent unchanged on every upload. */
@Serializable data class EventDto(
    val t: UtcInstant,
    /** Spec `EventType` (kept as a string). */
    val type: String,
    val clientEventId: String? = null,
    val active: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val dtc: String? = null,
    val value: Double? = null,
)

/** `TelemetryBatch`: samples and events of one trip, de-duplicated by [clientBatchId]. */
@Serializable data class TelemetryBatchDto(
    val clientBatchId: String,
    val clientTripId: String,
    /** The vehicle's server id. */
    val vehicleId: String,
    val samples: List<SampleDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
)

/** `TelemetryBatchAck` (`202`). */
@Serializable data class TelemetryBatchAckDto(
    val clientBatchId: String,
    /** accepted, duplicate or trip_deleted (kept as a string). */
    val status: String,
    val acceptedSamples: Int,
    val acceptedEvents: Int,
    val droppedSamples: Int? = null,
    val droppedEvents: Int? = null,
) {
    val tripDeleted: Boolean get() = status == STATUS_TRIP_DELETED

    companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_DUPLICATE = "duplicate"
        const val STATUS_TRIP_DELETED = "trip_deleted"
    }
}

// --- DTC reports (POST /v1/vehicles/{vehicleId}/dtc-reports) ---

/** `DtcReportCreate` (`DtcSet` + the read). Codes must match `^[PCBU][0-9A-F]{4}$`. */
@Serializable data class DtcReportCreateDto(
    val clientReportId: String,
    /** `read` or `cleared`. */
    val kind: String,
    val readAt: UtcInstant,
    /** MIL on / off, or null when unknown (not read, or a `cleared` report). Always on the wire, as `null` too. */
    val mil: Boolean? = null,
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val permanent: List<String>? = null,
    val vin: String? = null,
    val clientTripId: String? = null,
    val odometerKm: Int? = null,
    val distanceSinceMilKm: Int? = null,
) {
    /**
     * The request body: `mil` is required and nullable, so an unknown MIL goes as `"mil": null` ([BackendJson]
     * leaves nulls out everywhere else).
     */
    object Wire : JsonTransformingSerializer<DtcReportCreateDto>(serializer()) {
        override fun transformSerialize(element: JsonElement): JsonElement =
            JsonObject(element.jsonObject.toMutableMap().apply { putIfAbsent("mil", JsonNull) })
    }

    companion object {
        const val KIND_READ = "read"
        const val KIND_CLEARED = "cleared"
        val CODE = Regex("^[PCBU][0-9A-F]{4}$")
        val VIN = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    }
}

/** `DtcReport` (`200` replay or `201` created). */
@Serializable data class DtcReportDto(
    val id: String,
    val vehicleId: String,
    val createdAt: UtcInstant,
    val clientReportId: String,
    val kind: String,
    val readAt: UtcInstant,
    /** Always present, null when unknown. */
    val mil: Boolean?,
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val permanent: List<String>? = null,
    val vin: String? = null,
    val clientTripId: String? = null,
    val odometerKm: Int? = null,
    val distanceSinceMilKm: Int? = null,
)
