package io.github.neotheon3.orangebox.feature.trips

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
    )

    fun detail(located: Boolean = true) = TripDetails.detail(trip, samples(located), events(located), TEST_ZONE)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-port-xhdpi")
class TripDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: TripDetailUiState, dark: Boolean = false) {
        compose.setContent { OrangeBoxTheme(darkTheme = dark) { TripDetailScreen(state, locale = Locale.UK) } }
    }

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/trips/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `route map, metrics and events`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail()))
        compose.onNodeWithText("9.4 km").assertIsDisplayed()
        compose.onNodeWithText("0:25:00").assertIsDisplayed()
        compose.onNodeWithText("22.6 km/h").assertIsDisplayed()
        compose.onNodeWithText("13.4 km/L").assertIsDisplayed()
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        compose.onNodeWithText("1500 GPS points · 1500 speed readings").assertIsDisplayed()
        render("trip-detail-metrics")
        compose.onNodeWithTag("trip_detail_list").performScrollToNode(hasTestTag("trip_event_2"))
        compose.onNodeWithText("Harsh braking").assertIsDisplayed()
        // The peak and duration (#164); a row recorded before #164 has no duration.
        compose.onNodeWithText("09:20:40 · peak 5.5 m/s² · 2.4 s").assertIsDisplayed()
        compose.onNodeWithText("09:25:00 · peak 121.0 km/h · 18.0 s").assertIsDisplayed()
        compose.onNodeWithText("09:13:20 · peak 3.4 m/s²").assertExists()
    }

    @Test fun `deleted trip`() {
        show(TripDetailUiState(loading = false, notFound = true))
        compose.onNodeWithTag("trip_detail_not_found").assertIsDisplayed()
    }

    @Test fun `dark theme`() {
        show(TripDetailUiState(loading = false, detail = DetailFixture.detail()), dark = true)
        render("trip-detail-dark")
    }

}
