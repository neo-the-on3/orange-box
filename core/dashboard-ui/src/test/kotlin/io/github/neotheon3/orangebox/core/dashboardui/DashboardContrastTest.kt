package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.ui.graphics.Color
import io.github.neotheon3.orangebox.core.testing.Wcag
import io.github.neotheon3.orangebox.core.testing.contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** #71: the dashboard palette (always dark, legacy style) meets WCAG AA where text sits on it. */
class DashboardContrastTest {
    private fun check(min: Double, pairs: Map<String, Pair<Color, Color>>) {
        val low = pairs.mapNotNull { (name, c) -> contrast(c.first, c.second).takeIf { it < min }?.let { "$name = %.2f".format(it) } }
        assertTrue("Below $min:1: $low", low.isEmpty())
    }

    @Test fun `text on the dashboard surfaces reaches 4_5 to 1`() = check(
        Wcag.TEXT,
        mapOf(
            "Text/Background" to (DashboardColors.Text to DashboardColors.Background),
            "Text/ScreenTop (cluster gradient, lightest)" to (DashboardColors.Text to DashboardColors.ScreenTop),
            "Text/CardFill" to (DashboardColors.Text to DashboardColors.CardFill),
            "Text/TileFill" to (DashboardColors.Text to DashboardColors.TileFill),
            "Text/DetailButtonOn" to (DashboardColors.Text to DashboardColors.DetailButtonOn),
            "Caption/CardFill" to (DashboardColors.Caption to DashboardColors.CardFill),
            "TileTitle/TileFill" to (DashboardColors.TileTitle to DashboardColors.TileFill),
            "TileTitle/CardFill" to (DashboardColors.TileTitle to DashboardColors.CardFill),
            "LampOffText/Background" to (DashboardColors.LampOffText to DashboardColors.Background),
            "LampOffText/CardFill" to (DashboardColors.LampOffText to DashboardColors.CardFill),
            "Accent/Background" to (DashboardColors.Accent to DashboardColors.Background),
        ),
    )

    @Test fun `icons, lamps and the unlit captions in a cluster reach 3 to 1`() = check(
        Wcag.LARGE_TEXT_OR_UI,
        mapOf(
            "LampOff icon/Background" to (DashboardColors.LampOff to DashboardColors.Background),
            "LampOffText/ScreenTop (header lamps, 14 sp bold)" to (DashboardColors.LampOffText to DashboardColors.ScreenTop),
            "Normal/Background" to (DashboardColors.Normal to DashboardColors.Background),
            "PageDotOff/Background" to (DashboardColors.PageDotOff to DashboardColors.Background),
            "Mil/CardFill" to (DashboardColors.Mil to DashboardColors.CardFill),
            "Obd/CardFill" to (DashboardColors.Obd to DashboardColors.CardFill),
        ),
    )
}
