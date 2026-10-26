package io.github.neotheon3.orangebox.feature.rankings

import androidx.annotation.StringRes
import io.github.neotheon3.orangebox.core.network.Periods
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import java.text.NumberFormat
import java.util.Locale

/** Labels and number formats of the member screens. The spec doesn't define each metric's unit; see [metricValue]. */
internal object MemberFormat {
    @StringRes fun period(p: String): Int = when (p) {
        Periods.DAY -> R.string.member_period_day
        Periods.WEEK -> R.string.member_period_week
        Periods.MONTH -> R.string.member_period_month
        Periods.YEAR -> R.string.member_period_year
        else -> R.string.member_period_all
    }

    @StringRes fun metric(m: String): Int = when (m) {
        RankingMetrics.DRIVING_SCORE -> R.string.member_metric_driving_score
        RankingMetrics.ECO_SPEED -> R.string.member_metric_eco_speed
        RankingMetrics.FUEL_CUT -> R.string.member_metric_fuel_cut
        RankingMetrics.HARSH_DRIVING -> R.string.member_metric_harsh_driving
        RankingMetrics.LEVEL -> R.string.member_metric_level
        RankingMetrics.POINTS -> R.string.member_metric_points
        RankingMetrics.FUEL_SAVED -> R.string.member_metric_fuel_saved
        else -> R.string.member_metric_best_trip
    }

    @StringRes fun category(c: String): Int = when (c) {
        "driving" -> R.string.member_category_driving
        "level" -> R.string.member_category_level
        "ranking" -> R.string.member_category_ranking
        "event" -> R.string.member_category_event
        "redemption" -> R.string.member_category_redemption
        "expiry" -> R.string.member_category_expiry
        "adjustment" -> R.string.member_category_adjustment
        else -> R.string.member_category_other
    }

    /**
     * The string resource and number for a ranking value. Assumed units (open question for the backend): scores
     * 0–100, eco speed and fuel cut as % of driving time, harsh driving per 100 km, fuel saved in litres.
     */
    fun metricValue(metric: String, value: Double, locale: Locale): Pair<Int?, String> = when (metric) {
        RankingMetrics.ECO_SPEED, RankingMetrics.FUEL_CUT -> R.string.member_value_percent to decimal(value, locale, 1)
        RankingMetrics.HARSH_DRIVING -> R.string.member_value_per_100km to decimal(value, locale, 1)
        RankingMetrics.LEVEL -> R.string.member_value_level to integer(value, locale)
        RankingMetrics.POINTS -> R.string.member_value_points to integer(value, locale)
        RankingMetrics.FUEL_SAVED -> R.string.member_value_litres to decimal(value, locale, 2)
        else -> null to decimal(value, locale, 1)
    }

    fun decimal(v: Double, locale: Locale, digits: Int): String =
        NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = digits; maximumFractionDigits = digits }.format(v)

    fun integer(v: Double, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(Math.round(v))

    fun integer(v: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(v)

    /** `h:mm:ss`, like the Trips screens. */
    fun elapsed(seconds: Long): String = "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds % 3600 / 60, seconds % 60)
}
