package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.neotheon3.orangebox.feature.trips.stats.PeriodStats
import io.github.neotheon3.orangebox.feature.trips.stats.StatsPeriod
import io.github.neotheon3.orangebox.feature.trips.stats.StatsScreen
import io.github.neotheon3.orangebox.feature.trips.stats.StatsUiState
import io.github.neotheon3.orangebox.core.database.TripPeriodTotals
import io.github.neotheon3.orangebox.feature.trips.stats.TripStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

/** M10 gallery (#71/#72): Trips, trip detail and stats in light, dark and 200 % font, with accessibility checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class TripsGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val trips = listOf(
        trip(1, LocalDateTime.of(2026, 10, 3, 8, 5), minutes = 32, km = 14.2, litres = 1.1),
        trip(2, LocalDateTime.of(2026, 10, 12, 7, 55), minutes = 25, km = 9.4, litres = 0.8, harshBrake = 1),
        trip(3, LocalDateTime.of(2026, 10, 21, 9, 10), minutes = 64, km = 52.3, litres = 3.4, overspeed = 1),
    )

    @Test fun tripsMonth() = compose.screenGallery("trips-month") {
        TripsScreen(
            TripsCalendar.state(trips, YearMonth.of(2026, 10), null, TEST_ZONE, LocalDate.of(2026, 10, 24), DayOfWeek.MONDAY),
            TripsActions(), Locale.UK,
        )
    }

    @Test fun tripDetail() = compose.screenGallery("trip-detail") {
        TripDetailScreen(TripDetailUiState(loading = false, detail = DetailFixture.detail(), tilesEnabled = false), locale = Locale.UK)
    }

    @Test fun stats() = compose.screenGallery("stats-week") {
        val today = LocalDate.of(2026, 10, 25)
        StatsScreen(
            StatsUiState(
                loading = false, period = StatsPeriod.WEEK,
                periods = TripStats.ranges(StatsPeriod.WEEK, today, 8, DayOfWeek.MONDAY).mapIndexed { i, r ->
                    PeriodStats(r, TripPeriodTotals(trips = 3 + i % 3, distanceKm = 42.0 + i * 9.5, durationS = 3_900.0 + i * 600, fuelLitres = 3.1 + i * 0.4, ecoS = 2_100.0, harshBrake = 1))
                },
            ),
            locale = Locale.UK,
        )
    }
}
