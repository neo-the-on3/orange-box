package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #71: TalkBack reads the meters as values with spelled-out units, and "no data" instead of "---". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpokenMetersTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: DashboardUiState) = compose.setContent {
        DashboardTheme(DashboardSettings(reduceMotion = true)) {
            Column {
                RpmCluster(state, Modifier.size(234.dp, 232.dp))
                SpeedCluster(state, Modifier.size(234.dp, 232.dp))
            }
        }
    }

    @Test fun `miles per hour`() {
        show(DashboardUiState(live = true, rpm = "2100", speed = "37", speedUnit = "mph"))
        compose.onNodeWithContentDescription("Speed 37 miles per hour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Engine speed 2100 rpm").assertIsDisplayed()
    }

    @Test fun `no data`() {
        show(DashboardUiState())
        compose.onNodeWithContentDescription("Speed, no data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Engine speed, no data").assertIsDisplayed()
    }
}
