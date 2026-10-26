package io.github.neotheon3.orangebox.feature.rankings

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.FuelTypes
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.testing.FakeMemberBackend
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** The member screens in Fake mode (#66 / #68), driven by the real ViewModel over the in-process server. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp-port-xhdpi")
class RankingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val server = FakeBackendApi(clock = { now })

    private fun seed() = runBlocking {
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

    private fun show(dark: Boolean = false, fake: Boolean = true): RankingsViewModel {
        val vm = RankingsViewModel(MemberRepository(FakeMemberBackend(server)) { ZoneOffset.UTC }, flowOf(true), flowOf(fake))
        compose.setContent {
            val state by vm.uiState.collectAsState()
            OrangeBoxTheme(darkTheme = dark) {
                RankingsScreen(
                    state,
                    RankingsActions(
                        onTab = vm::selectTab, onRetry = vm::retry, onSummaryPeriod = vm::setSummaryPeriod, onMetric = vm::setMetric,
                        onRankingPeriod = vm::setRankingPeriod, onLoadMoreRanking = vm::loadMoreRanking, onOptIn = vm::setRankingOptIn,
                        onLoadMoreLedger = vm::loadMoreLedger,
                    ),
                    locale = Locale.UK, zone = ZoneOffset.UTC,
                )
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/rankings/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `summary of the week`() {
        seed()
        show()
        compose.onNodeWithTag("member_fake_note").assertIsDisplayed()
        compose.onNodeWithTag("member_summary").assertIsDisplayed()
        compose.onNodeWithText("Trips").assertIsDisplayed()
        compose.onNodeWithTag("member_stars").assertIsDisplayed()
        render("member-summary")
    }

    @Test fun `rankings - opt in puts me on the board`() {
        seed()
        show()
        compose.onNodeWithTag("member_tab_rankings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_optin").assertIsOff()
        compose.onNodeWithText("You're not ranked for this period").assertIsDisplayed()
        compose.onNodeWithTag("member_optin").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_optin").assertIsOn()
        compose.onNodeWithTag("member_my_rank").assertIsDisplayed()
        compose.onNodeWithTag("member_list").performScrollToNode(hasTestTag("member_row_me"))
        compose.onNodeWithTag("member_row_me").assertIsDisplayed()
        compose.onNodeWithTag("member_list").performScrollToNode(hasTestTag("member_my_rank"))
        render("member-rankings")
        compose.onNodeWithTag("member_metric_${RankingMetrics.HARSH_DRIVING}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_list").performScrollToNode(hasTestTag("member_row_1"))
        compose.onAllNodesWithText("per 100 km", substring = true).onFirst().assertExists()
    }

    @Test fun `points - balance and history, dark`() {
        seed()
        show(dark = true)
        compose.onNodeWithTag("member_tab_points").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_balance").assertIsDisplayed()
        compose.onNodeWithText("Welcome bonus").assertExists()
        render("member-points-dark")
    }

    @Test fun `a server without rankings shows not available`() {
        server.memberFeaturesImplemented = false
        show(fake = false)
        compose.onNodeWithTag("member_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("member_tab_rankings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_unavailable").assertIsDisplayed()
        render("member-unavailable")
    }

    @Test fun `a failure offers retry`() {
        server.arm(FakeBackendApi.Scenario.PROBLEM_500)
        show()
        compose.onNodeWithTag("member_failed").assertIsDisplayed()
        compose.onNodeWithTag("member_retry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("member_summary").assertIsDisplayed()
    }

    @Test fun `signed out shows the sign-in hint`() {
        compose.setContent { OrangeBoxTheme { RankingsScreen(RankingsUiState(available = false), RankingsActions(), Locale.UK) } }
        compose.onNodeWithTag("member_signed_out").assertIsDisplayed()
    }
}
