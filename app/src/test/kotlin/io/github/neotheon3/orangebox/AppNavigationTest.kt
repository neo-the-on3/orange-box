package io.github.neotheon3.orangebox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real app graph (Hilt + DataStore) and walks Home -> Settings -> Home on API 24 and 34. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [24, 34])
class AppNavigationTest {
    private val hilt = HiltAndroidRule(this)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hilt).around(compose)

    @Before fun inject() = hilt.inject()

    /** First launch: walk the onboarding (accept, skip car and dongle) to reach Home. */
    private fun walkOnboarding() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("onboarding_next")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("onboarding_welcome").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_disclaimer_accept").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_permissions").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_skip").performClick() // vehicle
        compose.onNodeWithTag("onboarding_skip").performClick() // dongle
        compose.onNodeWithTag("onboarding_finish").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("Backend: Off") > 0 }
    }

    @Test fun `first launch walks onboarding, then home opens settings and setup rows`() {
        walkOnboarding()
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithTag("settings_vehicle").performScrollTo().performClick()
        compose.onNodeWithTag("vehicles_screen").assertIsDisplayed()
        compose.onNodeWithTag("vehicles_add").performClick()
        compose.onNodeWithTag("vehicle_screen").assertIsDisplayed()
        compose.onNodeWithTag("setup_back").performClick()
        compose.onNodeWithTag("vehicles_screen").assertIsDisplayed()
        compose.onNodeWithTag("setup_back").performClick()
        compose.onNodeWithTag("settings_dongle").performScrollTo().performClick()
        compose.onNodeWithTag("dongle_screen").assertIsDisplayed()
        compose.onNodeWithTag("setup_back").performClick()
        compose.onNodeWithTag("settings_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("permissions_screen").assertIsDisplayed()
    }

    @Test fun `a driving notification tap opens the dashboard and back returns home`() {
        walkOnboarding()
        compose.activityRule.scenario.onActivity {
            it.handleOpenDashboard(
                android.content.Intent(it, MainActivity::class.java)
                    .putExtra(io.github.neotheon3.orangebox.driving.DrivingNotifications.EXTRA_OPEN_DASHBOARD, true),
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("dashboard_end_driving")).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("Backend: Off") > 0 }
    }

    @Test fun `home opens settings and back`() {
        walkOnboarding()
        compose.onNodeWithText("Backend: Off").assertIsDisplayed()
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithText("Server (advanced)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_mode_fake").performScrollTo().performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTextCount("Backend: Fake") > 0
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
