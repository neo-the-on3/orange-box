package io.github.neotheon3.orangebox.feature.rankings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.FuelTypes
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.testing.FakeMemberBackend
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** M10 gallery (#71/#72): the member summary (Fake mode) in light, dark and 200 % font, with accessibility checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class RankingsGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val server = FakeBackendApi(clock = { now })

    @Test fun summary() {
        runBlocking {
            val v = server.createVehicle(VehicleCreateDto("cv", fuelType = FuelTypes.GASOLINE, nickname = "Celerio"), "k")
            listOf(1L, 2L, 3L).forEach { i ->
                val start = now.minusSeconds(i * 7200)
                server.seedTrip(
                    TripUploadDto(
                        "t$i", v.id, TripUploadDto.STATE_COMPLETED, start, start.plusSeconds(2400), endedAt = start.plusSeconds(2400),
                        durationS = 2400, distanceKm = 18.0 + i, fuelUsedL = 1.6, ecoSpeedTimeS = 1300.0, idleTimeS = 200.0,
                        overSpeedTimeS = 60.0, fuelCutTimeS = 120.0, harshAccelCount = 1, maxSpeedKmh = 82.0,
                    ),
                )
            }
        }
        val vm = RankingsViewModel(MemberRepository(FakeMemberBackend(server)) { ZoneOffset.UTC }, flowOf(true), flowOf(true))
        compose.screenGallery("member-summary") {
            val state by vm.uiState.collectAsState()
            RankingsScreen(state, RankingsActions(onTab = vm::selectTab), locale = Locale.UK, zone = ZoneOffset.UTC)
        }
    }
}
