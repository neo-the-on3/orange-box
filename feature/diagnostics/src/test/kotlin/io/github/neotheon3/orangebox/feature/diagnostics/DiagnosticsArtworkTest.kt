package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardTheme
import io.github.neotheon3.orangebox.core.dashboardui.DashboardSettings
import io.github.neotheon3.orangebox.core.dashboardui.DtcLamp
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPrompt
import io.github.neotheon3.orangebox.core.diagnostics.DtcGroup
import io.github.neotheon3.orangebox.core.diagnostics.VehicleZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #100 screenshots per zone and lamp state, and #50 prompt states (build/outputs/diagnostics). Drawing
 * must not throw; the PNGs are for the artwork review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h1200dp-xhdpi")
class DiagnosticsArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `illustration per zone`() {
        val cases = listOf<Pair<String, Set<VehicleZone>>>("none" to emptySet()) + VehicleZone.entries.map { it.name.lowercase() to setOf(it) } +
            listOf("all" to VehicleZone.entries.toSet())
        compose.setContent {
            DashboardTheme(DashboardSettings(reduceMotion = true)) {
                Column(Modifier.background(Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cases.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (name, zones) ->
                                Column(Modifier.width(290.dp)) {
                                    Text(name, color = Color.White, fontSize = 11.sp)
                                    VehicleSystemsIllustration(zones, name, Modifier.width(290.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.shoot("illustration-zones")
    }

    @Test fun `lamps in ok, fault and pressed`() {
        compose.setContent {
            DashboardTheme(DashboardSettings(reduceMotion = true)) {
                Column(Modifier.background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("ok" to (false to false), "fault" to (true to false), "pressed" to (true to true)).forEach { (name, s) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                            DtcGroup.entries.forEach { g ->
                                DtcLamp(g.icon, g.label, faulted = s.first, contentDescription = g.label, onClick = {}, forcePressed = s.second)
                            }
                        }
                    }
                }
            }
        }
        compose.shoot("lamps-states")
    }

    @Test fun `prompt states`() {
        compose.setContent {
            DashboardTheme(DashboardSettings(reduceMotion = true)) {
                Column(Modifier.background(Color(0xFF333333)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(DiagnosingPrompt.Scanning, DiagnosingPrompt.NoTroubles, DiagnosingPrompt.Warning(3, milOn = true)).forEach {
                        Box(Modifier.width(340.dp)) { DiagnosingPromptCard(it, onDismiss = {}, onOpenDiagnostics = {}, reduceMotion = true) }
                    }
                }
            }
        }
        compose.shoot("prompt-states")
    }

    @Test fun `scanning sweep mid-way`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashboardTheme(DashboardSettings(reduceMotion = false)) {
                Box(Modifier.background(Color(0xFF333333)).padding(12.dp).width(340.dp)) {
                    DiagnosingPromptCard(DiagnosingPrompt.Scanning, onDismiss = {}, onOpenDiagnostics = {}, reduceMotion = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.shoot("prompt-scanning-sweep")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosingPromptCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `warning opens diagnostics from the car or the button, and closes`() {
        var opened = 0
        var dismissed = 0
        compose.setContent {
            DashboardTheme {
                DiagnosingPromptCard(DiagnosingPrompt.Warning(2, milOn = false), onDismiss = { dismissed++ }, onOpenDiagnostics = { opened++ }, reduceMotion = true)
            }
        }
        compose.onNodeWithTag("diagnosing_title").assertTextContains("DTC Warning")
        compose.onNodeWithTag("diagnosing_car").performClick()
        compose.onNodeWithTag("diagnosing_open").performClick()
        compose.onNodeWithTag("diagnosing_close").performClick()
        assertEquals(2, opened)
        assertEquals(1, dismissed)
    }

    @Test fun `scanning has a progress bar, no troubles none and no open button`() {
        compose.setContent { DashboardTheme { DiagnosingPromptCard(DiagnosingPrompt.Scanning, {}, {}, reduceMotion = true) } }
        compose.onNodeWithTag("diagnosing_title").assertTextContains("Car Diagnosing…")
        compose.onNodeWithTag("diagnosing_progress").assertIsDisplayed()
        compose.onNodeWithTag("diagnosing_open").assertDoesNotExist()
    }

    @Test fun `no troubles`() {
        compose.setContent { DashboardTheme { DiagnosingPromptCard(DiagnosingPrompt.NoTroubles, {}, {}, reduceMotion = true) } }
        compose.onNodeWithTag("diagnosing_title").assertTextContains("No Troubles")
        compose.onNodeWithTag("diagnosing_progress").assertDoesNotExist()
    }

    @Test fun `hidden draws nothing`() {
        compose.setContent { DashboardTheme { DiagnosingPromptCard(DiagnosingPrompt.Hidden, {}, {}, reduceMotion = true) } }
        compose.onNodeWithTag("diagnosing_prompt").assertDoesNotExist()
    }
}
