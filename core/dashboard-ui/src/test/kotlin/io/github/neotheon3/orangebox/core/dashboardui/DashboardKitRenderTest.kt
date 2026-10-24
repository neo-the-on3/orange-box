package io.github.neotheon3.orangebox.core.dashboardui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the kit with Robolectric native graphics and writes `build/outputs/dashboard-ui/kit.png`
 * for the artwork review (not a pixel-diff test). Fails only if drawing throws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h640dp-xhdpi")
class DashboardKitRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `render kit gallery`() {
        val s = DashboardUiState(
            live = true, rpm = "1698", speed = "59", fuelCut = true, ecoLevel = 1, coolant = IndicatorLevel.NORMAL,
            battery = IndicatorLevel.NORMAL, obdOn = true, tripKmPerL = "8.9", instantKmPerL = "12.0", instantFraction = 0.4f,
            fuelUsedL = "0.2", tripKm = "2.0", tripTime = "0:04:29", leftGlow = PanelGlow.FUEL_CUT,
        )
        compose.setContent {
            DashboardTheme(DashboardSettings(reduceMotion = true)) {
                Column(Modifier.background(DashboardColors.Background).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RpmCluster(s, Modifier.size(234.dp, 232.dp))
                        Column(Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConnectChips(s)
                            FuelCard(s)
                            TripCard(s)
                        }
                        SpeedCluster(s.copy(overspeed = true, rightGlow = PanelGlow.OVERSPEED, speed = "123"), Modifier.size(234.dp, 232.dp))
                        SpeedCluster(s.copy(harshBrake = true, rightGlow = PanelGlow.HARSH), Modifier.size(234.dp, 232.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PanelGlow.NONE, PanelGlow.ECO_YELLOW, PanelGlow.ECO_RED).forEach { g ->
                            RpmCluster(DashboardUiState(leftGlow = g), Modifier.size(160.dp, 150.dp), compact = true)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ValueTile("Battery Voltage", "14.0", "V", TileStatus.NORMAL, Modifier.size(170.dp, 70.dp), DashboardIcons.Battery, large = true)
                            ValueTile("Coolant Temp.", "105.0", "°C", TileStatus.WARNING, Modifier.size(170.dp, 70.dp), DashboardIcons.Coolant, large = true)
                            ValueTile("Fuel Level", "---", "%", TileStatus.UNSUPPORTED, Modifier.size(170.dp, 46.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                BottomBarButton(DashboardIcons.Hazard, "Emergency", {}, iconTint = DashboardColors.HazardRed)
                                BottomBarButton(DashboardIcons.MilEngine, "Diagnostics", {})
                                BottomBarButton(DashboardIcons.Power, "End driving", {})
                            }
                            BottomBarToggle("Detailed Info.", true, {}, "shown", "hidden")
                            PageDots(3, 0)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardIcons.all.forEach { Icon(it, it.name, tint = DashboardColors.Text, modifier = Modifier.size(40.dp)) }
                    }
                    LampStrip(s, Modifier.width(420.dp))
                }
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/outputs/dashboard-ui/kit.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
