package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h1100dp")
class DashboardKitTest {
    @get:Rule val compose = createComposeRule()

    private val state = DashboardUiState(
        live = true, rpm = "1698", speed = "59", fuelCut = true, overspeed = false, harshAccel = true, ecoLevel = 1,
        coolant = IndicatorLevel.WARNING, battery = IndicatorLevel.NORMAL, obdOn = true, tripKmPerL = "8.9",
        instantKmPerL = "30.0", instantFraction = 1f, fuelUsedL = "0.2", tripKm = "2.0", tripTime = "0:04:29",
        leftGlow = PanelGlow.FUEL_CUT, rightGlow = PanelGlow.HARSH,
    )

    private fun content(fontScale: Float, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            DashboardTheme(DashboardSettings(reduceMotion = true)) { body() }
        }
    }

    @Composable private fun clusters() {
        Column {
            RpmCluster(state, Modifier.size(234.dp, 232.dp))
            SpeedCluster(state, Modifier.size(234.dp, 232.dp))
            FuelCard(state, Modifier.size(110.dp, 130.dp))
            TripCard(state, Modifier.size(110.dp, 100.dp))
            ConnectChips(state, Modifier.width(110.dp))
        }
    }

    private fun assertDescriptions() {
        compose.onNodeWithContentDescription("Fuel-cut, active").assertIsDisplayed()
        compose.onNodeWithContentDescription("Overspeed, off").assertIsDisplayed()
        compose.onNodeWithContentDescription("Engine speed 1698 rpm").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speed 59 kilometres per hour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Coolant, warning").assertIsDisplayed()
        compose.onNodeWithContentDescription("Battery, on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Eco, green").assertIsDisplayed()
        compose.onNodeWithContentDescription("Accel., active").assertIsDisplayed()
        compose.onNodeWithContentDescription("Brake, off").assertIsDisplayed()
        compose.onNodeWithContentDescription("OBD, on").assertIsDisplayed()
        compose.onNodeWithContentDescription("MIL, off").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip economy 8.9 km/L, instant 30.0 km/L").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip 2.0 km, 0:04:29").assertIsDisplayed()
    }

    /** The meter stays inside its 234 dp cluster (auto-fit) at both scales. */
    private fun assertMetersFit() {
        listOf("rpm", "speed").forEach { m ->
            val meter = compose.onNodeWithTag("meter_$m").getUnclippedBoundsInRoot()
            val panel = compose.onNodeWithTag("cluster_$m").getUnclippedBoundsInRoot()
            assertTrue("$m meter fits horizontally", meter.left >= panel.left && meter.right <= panel.right)
            assertTrue("$m meter fits vertically", meter.top >= panel.top && meter.bottom <= panel.bottom)
        }
    }

    @Test fun `clusters and cards at font scale 1`() {
        content(1f) { clusters() }
        assertDescriptions()
        assertMetersFit()
    }

    @Test fun `clusters and cards at font scale 2`() {
        content(2f) { clusters() }
        assertDescriptions()
        assertMetersFit()
    }

    @Test fun `placeholders when there is no data`() {
        content(1f) { Column { RpmCluster(DashboardUiState(), Modifier.size(234.dp, 232.dp)); TripCard(DashboardUiState()) } }
        compose.onNodeWithContentDescription("Engine speed, no data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Coolant, no data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip --.- km, -:--:--").assertIsDisplayed()
    }

    @Test fun `value tiles announce state`() {
        content(2f) {
            Column {
                ValueTile("Battery Voltage", "12.0", "V", TileStatus.WARNING, Modifier.size(170.dp, 70.dp), DashboardIcons.Battery, large = true)
                ValueTile("O2 Sensor Volt.", "---", "V", TileStatus.UNSUPPORTED, Modifier.size(170.dp, 46.dp))
                ValueTile("Fuel Level", "---", "%", TileStatus.NO_DATA, Modifier.size(170.dp, 46.dp))
            }
        }
        compose.onNodeWithContentDescription("Battery Voltage, 12.0 V, warning").assertIsDisplayed()
        compose.onNodeWithContentDescription("O2 Sensor Volt., not supported").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fuel Level, no data").assertIsDisplayed()
    }

    @Test fun `bottom bar targets are at least 56 dp`() {
        var clicks = 0
        content(1f) {
            Column {
                BottomBarButton(DashboardIcons.Hazard, "Emergency", { clicks++ })
                BottomBarToggle("Detailed Info.", selected = true, onClick = { clicks++ }, stateOn = "shown", stateOff = "hidden")
            }
        }
        compose.onNodeWithContentDescription("Emergency").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Detailed Info.").assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "shown"))
            .performClick()
        compose.onNodeWithContentDescription("Emergency").performClick()
        assertEquals(2, clicks)
    }

    @Test fun `every icon is our own vector with content`() {
        assertEquals(17, DashboardIcons.all.size)
        DashboardIcons.all.forEach { assertTrue(it.name, it.root.size > 0) }
        assertEquals(DetailIcon.entries.size, DetailIcon.entries.map { it.vector }.size)
    }
}
