package io.github.neotheon3.orangebox.feature.trips.stats

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.database.TripPeriodTotals
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1100dp-port-xhdpi")
class StatsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 25)

    private fun state(period: StatsPeriod, empty: Boolean = false) = StatsUiState(
        loading = false,
        period = period,
        periods = TripStats.ranges(period, today, 8, DayOfWeek.MONDAY).mapIndexed { i, r ->
            PeriodStats(
                r,
                if (empty || i == 3) TripPeriodTotals() else TripPeriodTotals(
                    trips = 3 + i % 3, distanceKm = 42.0 + i * 9.5, durationS = 3_900.0 + i * 600, fuelLitres = 3.1 + i * 0.4,
                    ecoS = 2_100.0, harshAccel = i % 2, harshBrake = 1, overspeed = i % 3, maxSpeedKph = 96.0 + i,
                ),
            )
        },
    )

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `weekly totals, chart and earlier weeks`() {
        val picked = mutableListOf<StatsPeriod>()
        compose.setContent { OrangeBoxTheme { StatsScreen(state(StatsPeriod.WEEK), onPeriod = { picked += it }, locale = Locale.UK) } }
        compose.onNodeWithTag("stats_period_week").assertIsSelected()
        compose.onNodeWithText("This week").assertIsDisplayed()
        compose.onNodeWithText("42.0 km").assertIsDisplayed()
        compose.onNodeWithText("1:05:00").assertIsDisplayed()
        compose.onNodeWithText("13.5 km/L").assertIsDisplayed()
        compose.onNodeWithText("54%").assertIsDisplayed()
        compose.onNodeWithTag("stats_chart").assertIsDisplayed()
        render("stats-weekly")
        compose.onNodeWithTag("stats_period_month").performClick()
        assertEquals(listOf(StatsPeriod.MONTH), picked)
    }

    @Test fun `monthly, dark`() {
        compose.setContent { OrangeBoxTheme(darkTheme = true) { StatsScreen(state(StatsPeriod.MONTH), locale = Locale.UK) } }
        compose.onNodeWithText("This month").assertIsDisplayed()
        compose.onNodeWithText("October 2026 · 3 trips").assertIsDisplayed()
        render("stats-monthly-dark")
    }

    @Test fun `no trips yet`() {
        compose.setContent { OrangeBoxTheme { StatsScreen(state(StatsPeriod.WEEK, empty = true), locale = Locale.UK) } }
        compose.onNodeWithTag("stats_empty").assertIsDisplayed()
        render("stats-empty")
    }
}
