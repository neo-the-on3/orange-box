package io.github.neotheon3.orangebox.core.network

import kotlinx.serialization.Serializable

/*
 * Wire types of the Remote-only member features (#68): profile (`/v1/me`), stats summary, rankings and points.
 * Dates (`format: date`) stay strings (`yyyy-MM-dd`); open string sets (metric, period, category) stay strings
 * so a newer server can't break decoding.
 */

/** Spec `Profile`. [rankingOptIn] defaults to false, as in the spec. */
@Serializable data class ProfileDto(
    val id: String,
    val displayName: String,
    val email: String,
    val createdAt: UtcInstant,
    val updatedAt: UtcInstant,
    val countryCode: String? = null,
    val regionCode: String? = null,
    val rankingOptIn: Boolean = false,
    val authProvider: String? = null,
)

/** Spec `ProfilePatch` (JSON merge patch): only the fields to change; at least one. */
@Serializable data class ProfilePatchDto(
    val displayName: String? = null,
    val countryCode: String? = null,
    val regionCode: String? = null,
    val rankingOptIn: Boolean? = null,
)

/** Spec `Period` values. */
object Periods {
    const val DAY = "day"
    const val WEEK = "week"
    const val MONTH = "month"
    const val YEAR = "year"
    const val ALL = "all"
    val ALL_VALUES = listOf(DAY, WEEK, MONTH, YEAR, ALL)
}

/** Spec `RankingMetric` values. */
object RankingMetrics {
    const val DRIVING_SCORE = "driving_score"
    const val ECO_SPEED = "eco_speed"
    const val FUEL_CUT = "fuel_cut"
    const val HARSH_DRIVING = "harsh_driving"
    const val LEVEL = "level"
    const val POINTS = "points"
    const val FUEL_SAVED = "fuel_saved"
    const val BEST_TRIP = "best_trip"
    val ALL = listOf(DRIVING_SCORE, ECO_SPEED, FUEL_CUT, HARSH_DRIVING, LEVEL, POINTS, FUEL_SAVED, BEST_TRIP)
}

@Serializable data class ScoreStarsDto(
    val harshDriving: Int? = null,
    val idling: Int? = null,
    val normalSpeed: Int? = null,
    val ecoSpeed: Int? = null,
    val overSpeed: Int? = null,
    val highLoad: Int? = null,
    val fuelCut: Int? = null,
)

/** Provisional in the spec (REQUIREMENTS open question 11). */
@Serializable data class FuelCostDto(val amount: Double? = null, val saved: Double? = null, val currency: String? = null)

/** Spec `StatsSummary` (`GET /v1/stats/summary`). */
@Serializable data class StatsSummaryDto(
    val period: String,
    val from: String,
    val to: String,
    val tripCount: Int,
    val vehicleId: String? = null,
    val durationS: Long? = null,
    val distanceKm: Double? = null,
    val fuelUsedL: Double? = null,
    val fuelEconomyKmPerL: Double? = null,
    val harshAccelCount: Int? = null,
    val harshBrakeCount: Int? = null,
    val idleTimeS: Double? = null,
    val normalSpeedTimeS: Double? = null,
    val ecoSpeedTimeS: Double? = null,
    val overSpeedTimeS: Double? = null,
    val fuelCutTimeS: Double? = null,
    val highLoadTimeS: Double? = null,
    val maxSpeedKmh: Double? = null,
    val drivingScore: Double? = null,
    val level: Int? = null,
    val scoreStars: ScoreStarsDto? = null,
    val fuelCost: FuelCostDto? = null,
)

/** Spec `RankingEntry`: a display name only, never an e-mail address or login id. */
@Serializable data class RankingEntryDto(
    val rank: Int,
    val displayName: String,
    val value: Double,
    val isMe: Boolean,
    val regionCode: String? = null,
    val vehicleLabel: String? = null,
)

/** Spec `RankingPage` (`GET /v1/rankings/{metric}`). */
@Serializable data class RankingPageDto(
    val metric: String,
    val period: String,
    val periodStart: String,
    val items: List<RankingEntryDto>,
    val nextCursor: String? = null,
)

/** Spec `MyRanking` (`GET /v1/rankings/{metric}/me`; `404` = not ranked). */
@Serializable data class MyRankingDto(
    val metric: String,
    val period: String,
    val rank: Int,
    val participants: Int,
    val value: Double,
    val percentile: Double? = null,
)

@Serializable data class PointsByCategoryDto(val driving: Int? = null, val level: Int? = null, val ranking: Int? = null, val event: Int? = null)

/** Spec `PointsBalance` (`GET /v1/me/points`). */
@Serializable data class PointsBalanceDto(
    val earned: Int,
    val used: Int,
    val remaining: Int,
    val expiringSoon: Int? = null,
    val expiringAt: UtcInstant? = null,
    val byCategory: PointsByCategoryDto? = null,
)

/** Spec `PointsLedgerEntry`. [amount] is positive when earned, negative when used or expired. */
@Serializable data class PointsLedgerEntryDto(
    val id: String,
    val occurredAt: UtcInstant,
    val category: String,
    val amount: Int,
    val tripId: String? = null,
    val description: String? = null,
)

/** Spec `PointsLedgerPage` (`GET /v1/me/points/ledger`). */
@Serializable data class PointsLedgerPageDto(val items: List<PointsLedgerEntryDto>, val nextCursor: String? = null)
