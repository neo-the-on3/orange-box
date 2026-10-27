package io.github.neotheon3.orangebox.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.Wcag
import io.github.neotheon3.orangebox.core.testing.contrast
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #71: every text/background pair of the light and dark schemes meets WCAG AA (4.5:1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContrastTest {
    @get:Rule val compose = createComposeRule()

    private fun schemes(): Pair<ColorScheme, ColorScheme> {
        lateinit var light: ColorScheme
        lateinit var dark: ColorScheme
        compose.setContent {
            OrangeBoxTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
            OrangeBoxTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        return light to dark
    }

    private fun pairs(s: ColorScheme): Map<String, Pair<Color, Color>> = mapOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "primary/surface (text buttons, links)" to (s.primary to s.surface),
        "primary/background" to (s.primary to s.background),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "onBackground/background" to (s.onBackground to s.background),
        "error/surface" to (s.error to s.surface),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
    )

    @Test fun `light and dark schemes meet WCAG AA for text`() {
        val (light, dark) = schemes()
        val failures = listOf("light" to light, "dark" to dark).flatMap { (name, s) ->
            pairs(s).mapNotNull { (pair, c) ->
                val r = contrast(c.first, c.second)
                if (r < Wcag.TEXT) "$name $pair = %.2f".format(r) else null
            }
        }
        assertTrue("Below 4.5:1: $failures", failures.isEmpty())
    }

    @Test fun `outline reaches 3 to 1 for UI parts (text field borders, unchecked switches)`() {
        val (light, dark) = schemes()
        listOf(light, dark).forEach { assertTrue(contrast(it.outline, it.surface) >= Wcag.LARGE_TEXT_OR_UI) }
    }
}
