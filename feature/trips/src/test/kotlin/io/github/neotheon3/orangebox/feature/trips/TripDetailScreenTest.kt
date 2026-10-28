package io.github.neotheon3.orangebox.feature.trips

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ovh.plrapps.mapcompose.core.TileStreamProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Shared fixture: a 25-minute loop around Cubbon Park, Bengaluru, with three events. */
internal object DetailFixture {
    val start: LocalDateTime = LocalDateTime.of(2026, 10, 21, 9, 10)
    val trip = trip(4, start, minutes = 25, km = 9.4, litres = 0.7, harshAccel = 1, harshBrake = 1, overspeed = 1)
        .copy(maxSpeedKph = 92.0, co2Grams = 1_620.0, idleS = 140.0, ecoS = 610.0, overspeedS = 18.0, fuelCutS = 45.0)

    /** 1 Hz, speed rising and falling through every zone. */
    fun samples(located: Boolean = true): List<TripSampleEntity> = (0 until 1500).map { i ->
        val a = i / 1500.0 * 2 * Math.PI
        val speed = (45 + 40 * sin(a * 3)).coerceAtLeast(0.0).let { if (i in 300..360) 0.0 else it }
        TripSampleEntity(
            tripId = 4, timestampMillis = trip.startedAtMillis + i * 1000L, speedKph = speed, rpm = 900 + speed * 25, fuelFlowLph = 0.8 + speed / 25, coolantC = 88.0,
            latitude = if (located) 12.9763 + 0.012 * sin(a) + 0.002 * sin(a * 5) else null,
            longitude = if (located) 77.5929 + 0.016 * cos(a) else null,
            accuracyM = 6f,
        )
    }

    fun events(located: Boolean = true) = listOf(
        TripEventEntity(tripId = 4, timestampMillis = trip.startedAtMillis + 200_000, type = "harsh_accel", value = 3.4, latitude = if (located) 12.9853 else null, longitude = if (located) 77.5990 else null),
        TripEventEntity(tripId = 4, timestampMillis = trip.startedAtMillis + 640_000, type = "harsh_brake", value = -5.5, latitude = if (located) 12.9700 else null, longitude = if (located) 77.5810 else null, durationS = 2.4),
        TripEventEntity(tripId = 4, timestampMillis = trip.startedAtMillis + 900_000, type = "overspeed", value = 121.0, durationS = 18.0),
        TripEventEntity(tripId = 4, timestampMillis = trip.startedAtMillis + 1_200_000, type = "fuel_cut", value = 60.0, durationS = 12.0),
    )

    fun detail(located: Boolean = true) = TripDetails.detail(trip, samples(located), events(located), TEST_ZONE)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-port-xhdpi")
class TripDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    /** A stand-in tile so the screenshot shows the tile layer and attribution without network. */
    private val fakeTiles = TileStreamProvider { row, col, _ ->
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if ((row + col) % 2 == 0) 0xFFF2EFE9.toInt() else 0xFFE9E5DC.toInt())
        val road = Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 10f }
        c.drawLine(0f, 128f, 256f, 128f, road); c.drawLine(128f, 0f, 128f, 256f, road)
        ByteArrayInputStream(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
    }

    private fun show(state: TripDetailUiState, tiles: TileStreamProvider? = null, dark: Boolean = false, onTiles: (Boolean) -> Unit = {}) {
        compose.setContent { OrangeBoxTheme(darkTheme = dark) { TripDetailScreen(state, tiles, onTilesChanged = onTiles, locale = Locale.UK) } }
    }

    private fun render(name: String) {
        // Tiles decode on MapCompose's worker threads and land on the main looper.
        repeat(8) {
            Thread.sleep(150)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(250)
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `route map, metrics and events`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail()), fakeTiles)
        compose.onNodeWithTag("trip_map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Route map, 1500 recorded positions").assertIsDisplayed()
        compose.onNodeWithTag("trip_map_attribution", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("© OpenStreetMap contributors", useUnmergedTree = true).assertIsDisplayed()
        render("trip-detail-map")
        compose.onNodeWithText("9.4 km").assertIsDisplayed()
        compose.onNodeWithText("0:25:00").assertIsDisplayed()
        compose.onNodeWithText("22.6 km/h").assertIsDisplayed()
        compose.onNodeWithText("13.4 km/L").assertIsDisplayed()
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        compose.onNodeWithTag("trip_detail_list").performScrollToNode(hasTestTag("trip_detail_gps_points"))
        compose.onNodeWithText("1500 GPS points · 1500 speed readings").assertIsDisplayed()
        render("trip-detail-metrics")
        compose.onNodeWithTag("trip_detail_list").performScrollToNode(hasTestTag("trip_event_2"))
        compose.onNodeWithText("Harsh braking").assertIsDisplayed()
        // The peak and duration (#164); a row recorded before #164 has no duration.
        compose.onNodeWithText("09:20:40 · peak 5.5 m/s² · 2.4 s").assertIsDisplayed()
        compose.onNodeWithText("09:25:00 · peak 121.0 km/h · 18.0 s").assertIsDisplayed()
        compose.onNodeWithText("09:13:20 · peak 3.4 m/s²").assertExists()
        // Fuel cut in its own section, not an event (#165).
        compose.onNodeWithTag("trip_detail_list").performScrollToNode(hasTestTag("trip_fuel_cut_0"))
        compose.onNodeWithText("09:30:00 · 12.0 s").assertIsDisplayed()
        compose.onAllNodesWithTag("trip_event_3").assertCountEquals(0)
    }

    @Test fun `tiles off draws the route alone, without attribution`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail(), tilesEnabled = false), fakeTiles)
        compose.onNodeWithTag("trip_map").assertIsDisplayed()
        compose.onAllNodesWithTag("trip_map_attribution", useUnmergedTree = true).assertCountEquals(0)
        render("trip-detail-no-tiles")
    }

    @Test fun `a trip without location shows a hint instead of a map`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail(located = false)), fakeTiles)
        compose.onAllNodesWithTag("trip_map").assertCountEquals(0)
        compose.onNodeWithText("No route for this trip").assertIsDisplayed()
        compose.onNodeWithText("9.4 km").assertIsDisplayed()
        render("trip-detail-no-location")
    }

    @Test fun `the menu toggles map tiles`() {
        val changes = mutableListOf<Boolean>()
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail()), fakeTiles) { changes += it }
        compose.onNodeWithTag("trip_detail_more").performClick()
        compose.onNodeWithTag("trip_detail_tiles_toggle").performClick()
        assertEquals(listOf(false), changes)
    }

    @Test fun `#211 - the menu excludes a trip from stats and deletes it after a confirmation`() {
        val excluded = mutableListOf<Boolean>()
        var deletes = 0
        val detail = DetailFixture.detail()
        compose.setContent {
            OrangeBoxTheme {
                TripDetailScreen(
                    TripDetailUiState(loading = false, detail = detail), fakeTiles, locale = Locale.UK,
                    trip = TripActions(onExcludedChanged = { excluded += it }, onDelete = { deletes++ }),
                )
            }
        }
        compose.onNodeWithTag("trip_detail_more").performClick()
        compose.onNodeWithText("Exclude from stats").assertIsDisplayed()
        renderPopup("trip-detail-menu", androidx.compose.ui.test.isPopup())
        compose.onNodeWithTag("trip_detail_exclude_toggle").performClick()
        assertEquals(listOf(true), excluded)

        compose.onNodeWithTag("trip_detail_more").performClick()
        compose.onNodeWithTag("trip_detail_delete").performClick()
        compose.onNodeWithTag("trip_detail_delete_dialog").assertIsDisplayed()
        assertEquals("nothing deleted before the confirmation", 0, deletes)
        renderPopup("trip-detail-delete-confirm", androidx.compose.ui.test.isDialog())
        compose.onNodeWithTag("trip_detail_delete_confirm").performClick()
        assertEquals(1, deletes)
    }

    @Test fun `#211 - an excluded trip offers Include in stats, a trip still recording has no trip actions`() {
        val detail = DetailFixture.detail()
        show(TripDetailUiState(loading = false, detail = detail.copy(header = detail.header.copy(excludedFromStats = true))), fakeTiles)
        compose.onNodeWithTag("trip_detail_more").performClick()
        compose.onNodeWithText("Include in stats").assertIsDisplayed()
        compose.onAllNodesWithTag("trip_detail_delete").assertCountEquals(1)
    }

    @Test fun `#211 - no trip actions while recording`() {
        val detail = DetailFixture.detail()
        show(TripDetailUiState(loading = false, detail = detail.copy(header = detail.header.copy(end = null))), fakeTiles)
        compose.onNodeWithTag("trip_detail_more").performClick()
        compose.onAllNodesWithTag("trip_detail_exclude_toggle").assertCountEquals(0)
        compose.onAllNodesWithTag("trip_detail_delete").assertCountEquals(0)
    }

    /** A menu popup or dialog window, for the docs screenshots. */
    private fun renderPopup(name: String, window: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitForIdle()
        val bitmap = compose.onNode(window).captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `deleted trip`() {
        show(TripDetailUiState(loading = false, notFound = true))
        compose.onNodeWithTag("trip_detail_not_found").assertIsDisplayed()
    }

    @Test fun `dark theme`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail()), fakeTiles, dark = true)
        render("trip-detail-dark")
    }

    @Test fun `#203 - a trip of a vehicle deleted elsewhere offers a move`() {
        var moves = 0
        compose.setContent {
            OrangeBoxTheme {
                TripDetailScreen(
                    TripDetailUiState(loading = false, detail = DetailFixture.detail(), tilesEnabled = false, orphaned = true),
                    locale = Locale.UK,
                    move = MoveTripsActions(onStart = { moves++ }),
                )
            }
        }
        compose.onNodeWithTag("trips_orphaned").assertIsDisplayed()
        compose.onNodeWithText("Vehicle deleted on another device").assertIsDisplayed()
        render("trip-detail-orphaned")
        compose.onNodeWithTag("trips_move").assertTextContains("Move this trip").performClick()
        assertEquals(1, moves)
    }
}
