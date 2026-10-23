package io.github.neotheon3.orangebox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun `home opens settings and back`() {
        compose.onNodeWithText("Backend: Off").assertIsDisplayed()
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithText("Server (advanced)").assertIsDisplayed()
        compose.onNodeWithTag("settings_mode_fake").performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount("Backend: Fake") > 0
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
