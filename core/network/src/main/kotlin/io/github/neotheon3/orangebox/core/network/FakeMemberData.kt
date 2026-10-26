package io.github.neotheon3.orangebox.core.network

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Canned member data for Fake mode (#66): stats from the trips uploaded to the fake server, a leaderboard of
 * made-up drivers plus the member (when opted in), and a points ledger. Values are plausible, not a rule the
 * real server must follow: how points are earned and how each metric is scored is the backend's to define.
 */
internal object FakeMemberData {

    /** Made-up opted-in members on the fake leaderboards: display names only, as the spec requires. */
    val OTHERS: List<Pair<String, String?>> = listOf(
        "Asha" to "Maruti Suzuki Swift", "Ravi" to "Hyundai i20", "Meera" to "Tata Nexon", "Kabir" to "Honda City",
        "Ishaan" to null, "Nisha" to "Maruti Suzuki Baleno", "Arjun" to "Mahindra XUV300", "Priya" to "Kia Seltos",
        "Dev" to "Toyota Glanza", "Sara" to "Maruti Suzuki Celerio", "Vikram" to "Renault Kwid", "Anaya" to null,
    )

    /** Inclusive day range of [period] around [date] (weeks start on Monday, like ISO 8601). */
    fun range(period: String, date: LocalDate, firstTrip: LocalDate?): Pair<LocalDate, LocalDate> = when (period) {
        Periods.DAY -> date to date
        Periods.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        Periods.MONTH -> date.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        Periods.YEAR -> date.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
        Periods.ALL -> (firstTrip ?: date).let { min(it, date) } to date
        else -> throw IllegalArgumentException(period)
    }

    private fun min(a: LocalDate, b: LocalDate) = if (a.isBefore(b)) a else b

    fun zone(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrDefault(ZoneOffset.UTC)

    fun completed(trips: Collection<TripDto>) = trips.filter { it.state == TripUploadDto.STATE_COMPLETED }

    fun inRange(trips: Collection<TripDto>, zone: ZoneId, from: LocalDate, to: LocalDate) = trips.filter {
        val day = it.startedAt.atZone(zone).toLocalDate()
        !day.isBefore(from) && !day.isAfter(to)
    }

    /** 0–100: starts at 100, loses points for harsh events per 100 km, over-speed and idle shares. */
    fun drivingScore(trips: List<TripDto>): Double? {
        val km = trips.sumOf { it.distanceKm ?: 0.0 }
        val time = trips.sumOf { (it.durationS ?: 0L).toDouble() }
        if (trips.isEmpty() || time <= 0) return null
        val harshPer100 = trips.sumOf { (it.harshAccelCount ?: 0) + (it.harshBrakeCount ?: 0) } * 100.0 / max(km, 1.0)
        val over = trips.sumOf { it.overSpeedTimeS ?: 0.0 } / time
        val idle = trips.sumOf { it.idleTimeS ?: 0.0 } / time
        return (100 - harshPer100 * 2 - over * 60 - idle * 30).coerceIn(0.0, 100.0).round1()
    }

    fun level(allTrips: List<TripDto>): Int = 1 + (allTrips.sumOf { it.distanceKm ?: 0.0 } / 100).toInt()

    private fun stars(share: Double, goodIsHigh: Boolean): Int =
        ((if (goodIsHigh) share else 1 - share) * 5).roundToInt().coerceIn(0, 5)

    fun summary(period: String, from: LocalDate, to: LocalDate, trips: List<TripDto>, all: List<TripDto>, vehicleId: String?): StatsSummaryDto {
        val time = trips.sumOf { (it.durationS ?: 0L).toDouble() }
        val km = trips.sumOf { it.distanceKm ?: 0.0 }
        val fuel = trips.mapNotNull { it.fuelUsedL }.takeIf { it.isNotEmpty() }?.sum()
        fun share(f: (TripDto) -> Double?) = if (time > 0) trips.sumOf { f(it) ?: 0.0 } / time else 0.0
        val eco = trips.sumOf { it.ecoSpeedTimeS ?: 0.0 }
        val over = trips.sumOf { it.overSpeedTimeS ?: 0.0 }
        val idle = trips.sumOf { it.idleTimeS ?: 0.0 }
        val harsh = trips.sumOf { (it.harshAccelCount ?: 0) + (it.harshBrakeCount ?: 0) }
        return StatsSummaryDto(
            period = period, from = from.toString(), to = to.toString(), tripCount = trips.size, vehicleId = vehicleId,
            durationS = time.toLong(), distanceKm = km.round1(), fuelUsedL = fuel?.round2(),
            fuelEconomyKmPerL = fuel?.takeIf { it > 0 }?.let { (km / it).round1() },
            harshAccelCount = trips.sumOf { it.harshAccelCount ?: 0 }, harshBrakeCount = trips.sumOf { it.harshBrakeCount ?: 0 },
            idleTimeS = idle, normalSpeedTimeS = max(0.0, time - eco - over - idle), ecoSpeedTimeS = eco, overSpeedTimeS = over,
            fuelCutTimeS = trips.sumOf { it.fuelCutTimeS ?: 0.0 }, highLoadTimeS = 0.0,
            maxSpeedKmh = trips.mapNotNull { it.maxSpeedKmh }.maxOrNull(),
            drivingScore = drivingScore(trips), level = level(all),
            scoreStars = if (trips.isEmpty()) null else ScoreStarsDto(
                harshDriving = (5 - harsh * 50.0 / max(km, 1.0)).roundToInt().coerceIn(0, 5),
                idling = stars(share { it.idleTimeS }, goodIsHigh = false),
                normalSpeed = stars(1 - share { it.overSpeedTimeS } - share { it.idleTimeS }, goodIsHigh = true),
                ecoSpeed = stars(share { it.ecoSpeedTimeS }, goodIsHigh = true),
                overSpeed = stars(share { it.overSpeedTimeS }, goodIsHigh = false),
                highLoad = 5,
                fuelCut = stars(min(1.0, share { it.fuelCutTimeS } * 5), goodIsHigh = true),
            ),
        )
    }

    /** True when a lower value ranks higher (fewer harsh events per 100 km). */
    fun lowerIsBetter(metric: String) = metric == RankingMetrics.HARSH_DRIVING

    /** The member's value for [metric] over [trips] (null: nothing to rank). [all] is every trip, for level and points. */
    fun myValue(metric: String, trips: List<TripDto>, all: List<TripDto>, points: Int): Double? {
        if (trips.isEmpty()) return null
        val time = trips.sumOf { (it.durationS ?: 0L).toDouble() }.takeIf { it > 0 } ?: return null
        val km = trips.sumOf { it.distanceKm ?: 0.0 }
        return when (metric) {
            RankingMetrics.DRIVING_SCORE -> drivingScore(trips)
            RankingMetrics.ECO_SPEED -> (trips.sumOf { it.ecoSpeedTimeS ?: 0.0 } * 100 / time).round1()
            RankingMetrics.FUEL_CUT -> (trips.sumOf { it.fuelCutTimeS ?: 0.0 } * 100 / time).round1()
            RankingMetrics.HARSH_DRIVING -> (trips.sumOf { (it.harshAccelCount ?: 0) + (it.harshBrakeCount ?: 0) } * 100.0 / max(km, 1.0)).round1()
            RankingMetrics.LEVEL -> level(all).toDouble()
            RankingMetrics.POINTS -> points.toDouble()
            // Litres a fuel cut saved, assuming ~1 L/h at idle-equivalent flow.
            RankingMetrics.FUEL_SAVED -> (trips.sumOf { it.fuelCutTimeS ?: 0.0 } / 3600).round2()
            RankingMetrics.BEST_TRIP -> trips.mapNotNull { drivingScore(listOf(it)) }.maxOrNull()
            else -> null
        }
    }

    /** A made-up member's value: stable per (name, metric, period), in the metric's usual range. */
    fun otherValue(index: Int, metric: String, period: String): Double {
        val seed = ((OTHERS[index].first + metric + period).hashCode().toLong() and 0x7fffffff) % 1000 / 1000.0
        return when (metric) {
            RankingMetrics.DRIVING_SCORE, RankingMetrics.BEST_TRIP -> (55 + seed * 44).round1()
            RankingMetrics.ECO_SPEED -> (20 + seed * 60).round1()
            RankingMetrics.FUEL_CUT -> (2 + seed * 15).round1()
            RankingMetrics.HARSH_DRIVING -> (seed * 8).round1()
            RankingMetrics.LEVEL -> (1 + (seed * 30).toInt()).toDouble()
            RankingMetrics.POINTS -> (200 + (seed * 4800).toInt()).toDouble()
            RankingMetrics.FUEL_SAVED -> (seed * 6).round2()
            else -> seed
        }
    }

    /** Leaderboard rows (ranks 1…n), ties share a rank. [me] is the member's display name and value, or null. */
    fun leaderboard(metric: String, period: String, me: Pair<String, Double>?): List<RankingEntryDto> {
        val rows = OTHERS.indices.map { Triple(OTHERS[it].first, OTHERS[it].second, otherValue(it, metric, period)) to false } +
            listOfNotNull(me?.let { Triple(it.first, null as String?, it.second) to true })
        val sorted = rows.sortedWith(compareBy<Pair<Triple<String, String?, Double>, Boolean>> { if (lowerIsBetter(metric)) it.first.third else -it.first.third }.thenBy { it.first.first })
        var rank = 0
        var previous: Double? = null
        return sorted.mapIndexed { i, (row, isMe) ->
            if (row.third != previous) rank = i + 1
            previous = row.third
            RankingEntryDto(rank = rank, displayName = row.first, value = row.third, isMe = isMe, vehicleLabel = row.second)
        }
    }

    /** Ledger entries newest first: a welcome bonus, 10 per completed trip + 1 per km, and one canned redemption. */
    fun ledger(joinedAt: Instant, trips: List<TripDto>): List<PointsLedgerEntryDto> {
        val entries = mutableListOf(
            PointsLedgerEntryDto("pt_welcome", joinedAt, "event", 500, description = "Welcome bonus"),
            PointsLedgerEntryDto("pt_redeem", joinedAt.plusSeconds(60), "redemption", -200, description = "Fuel voucher (demo)"),
        )
        trips.sortedBy { it.startedAt }.forEach { t ->
            entries += PointsLedgerEntryDto(
                id = "pt_" + t.id, occurredAt = t.endedAt ?: t.snapshotAt, category = "driving",
                amount = 10 + (t.distanceKm ?: 0.0).toInt(), tripId = t.id, description = "Trip points",
            )
        }
        return entries.sortedByDescending { it.occurredAt }
    }

    fun balance(ledger: List<PointsLedgerEntryDto>): PointsBalanceDto {
        val earned = ledger.filter { it.amount > 0 }.sumOf { it.amount }
        val used = -ledger.filter { it.amount < 0 }.sumOf { it.amount }
        fun cat(c: String) = ledger.filter { it.category == c && it.amount > 0 }.sumOf { it.amount }
        return PointsBalanceDto(
            earned = earned, used = used, remaining = earned - used, expiringSoon = 0, expiringAt = null,
            byCategory = PointsByCategoryDto(driving = cat("driving"), level = cat("level"), ranking = cat("ranking"), event = cat("event")),
        )
    }

    private fun Double.round1() = (this * 10).roundToInt() / 10.0
    private fun Double.round2() = (this * 100).roundToInt() / 100.0
}
