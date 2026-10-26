package io.github.neotheon3.orangebox.feature.trips

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.Vehicle
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
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class TripsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 24)
    private val events = mutableListOf<String>()
    private val actions = TripsActions(
        onBack = { events += "back" },
        onPreviousMonth = { events += "prev" },
        onNextMonth = { events += "next" },
        onDayClicked = { events += "day:$it" },
        onOpenTrip = { events += "trip:$it" },
        move = MoveTripsActions(
            onStart = { events += "move" },
            onSelect = { events += "pick:$it" },
            onConfirm = { events += "confirm" },
            onDismiss = { events += "cancel" },
        ),
    )

    private val populated = listOf(
        trip(1, LocalDateTime.of(2026, 10, 3, 8, 5), minutes = 32, km = 14.2, litres = 1.1),
        trip(2, LocalDateTime.of(2026, 10, 3, 18, 40), minutes = 41, km = 18.9, litres = 1.6, harshBrake = 1),
        trip(3, LocalDateTime.of(2026, 10, 12, 7, 55), minutes = 25, km = 9.4, litres = 0.8),
        trip(4, LocalDateTime.of(2026, 10, 21, 9, 10), minutes = 64, km = 52.3, litres = 3.4, harshAccel = 2, overspeed = 1),
        trip(5, LocalDateTime.of(2026, 10, 24, 19, 30), minutes = 12, km = 4.1, litres = 0.4, open = true),
    )

    private fun show(state: TripsUiState, dark: Boolean = false) {
        compose.setContent { OrangeBoxTheme(darkTheme = dark) { TripsScreen(state, actions, Locale.UK) } }
    }

    private fun state(trips: List<io.github.neotheon3.orangebox.core.database.TripEntity>, month: YearMonth = YearMonth.of(2026, 10), day: LocalDate? = null) =
        TripsCalendar.state(trips, month, day, TEST_ZONE, today, DayOfWeek.MONDAY)

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `empty month shows the calendar without markers and an empty message`() {
        show(state(emptyList(), YearMonth.of(2026, 11)))
        compose.onNodeWithTag("trips_month_title").assertTextContains("November 2026")
        compose.onNodeWithTag("trips_empty").assertIsDisplayed().assertTextContains("No trips in November 2026")
        compose.onAllNodesWithTag("trips_day_marker", useUnmergedTree = true).assertCountEquals(0)
        render("trips-empty-month")
    }

    @Test fun `populated month marks days with trips and lists them newest first`() {
        show(state(populated))
        compose.onNodeWithTag("trips_month_title").assertTextContains("October 2026")
        compose.onAllNodesWithTag("trips_day_marker", useUnmergedTree = true).assertCountEquals(4)
        compose.onNodeWithTag("trips_day_2026-10-03").assertContentDescriptionContains("2 trips", substring = true)
        compose.onNodeWithText("5 trips · 98.9 km · 2:54:00").assertIsDisplayed()
        compose.onNodeWithText("52.3 km · 1:04:00 · 15.4 km/L · eco 0:00:00").assertIsDisplayed()
        compose.onNodeWithTag("trips_day_header_2026-10-21").assertExists()
        compose.onNodeWithText("Wed 21 Oct, 09:10 – 10:14").assertIsDisplayed()
        compose.onNodeWithText("3 events").assertIsDisplayed()
        compose.onAllNodesWithTag("trips_empty").assertCountEquals(0)
        render("trips-month")
    }

    @Test fun `selected day filters the list and is announced as selected`() {
        show(state(populated, day = LocalDate.of(2026, 10, 3)))
        compose.onNodeWithTag("trips_day_2026-10-03").assertIsSelected()
        compose.onAllNodesWithTag("trip_row_4").assertCountEquals(0)
        compose.onNodeWithTag("trip_row_2").assertIsDisplayed()
        compose.onAllNodesWithTag("trips_day_header_2026-10-03").assertCountEquals(0)
        render("trips-day-selected")
    }

    @Test fun `controls call their actions`() {
        show(state(populated))
        compose.onNodeWithTag("trips_prev_month").performClick()
        compose.onNodeWithTag("trips_next_month").performClick()
        compose.onNodeWithTag("trips_day_2026-10-12").performClick()
        compose.onNodeWithTag("trip_row_3").performClick()
        compose.onNodeWithTag("trips_back").performClick()
        assertEquals(listOf("prev", "next", "day:2026-10-12", "trip:3", "back"), events)
    }

    @Test fun `dark theme`() {
        show(state(populated), dark = true)
        render("trips-month-dark")
    }

    @Test fun `#203 - trips of a vehicle deleted elsewhere get a hint and a move action`() {
        show(state(populated).copy(orphanedTripIds = setOf(3L, 4L)))
        compose.onNodeWithTag("trips_orphaned").assertIsDisplayed()
        compose.onNodeWithText("2 trips belong to a vehicle", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("trip_orphaned_4", useUnmergedTree = true).assertExists().assertTextContains("Vehicle deleted on another device")
        compose.onAllNodesWithTag("trip_orphaned_1", useUnmergedTree = true).assertCountEquals(0)
        render("trips-orphaned")
        compose.onNodeWithTag("trips_move").performClick()
        assertEquals(listOf("move"), events)
    }

    @Test fun `#203 - no banner without orphaned trips`() {
        show(state(populated))
        compose.onAllNodesWithTag("trips_orphaned").assertCountEquals(0)
    }

    @Test fun `#203 - the move dialog lists the vehicles and confirms the pick`() {
        val dialog = MoveTripsDialog(setOf(3L, 4L), listOf(Vehicle(id = 5, name = "Celerio"), Vehicle(id = 6, name = "Swift", maker = "Maruti", model = "Swift")), selected = 6)
        show(state(populated).copy(orphanedTripIds = setOf(3L, 4L), moveDialog = dialog))
        compose.onNodeWithText("Move 2 trips").assertIsDisplayed()
        compose.onNodeWithTag("trips_move_target_5").performClick()
        compose.onNodeWithTag("trips_move_confirm").performClick()
        compose.onNodeWithTag("trips_move_cancel").performClick()
        assertEquals(listOf("pick:5", "confirm", "cancel"), events)
    }

    @Test fun `#203 - with no other vehicle the dialog says so and can't confirm`() {
        show(state(populated).copy(orphanedTripIds = setOf(3L), moveDialog = MoveTripsDialog(setOf(3L), emptyList(), null)))
        compose.onNodeWithTag("trips_move_none").assertIsDisplayed()
        compose.onAllNodesWithTag("trips_move_confirm").assertCountEquals(0)
    }
}
