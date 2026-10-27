package io.github.neotheon3.orangebox.core.testing

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import java.io.File

/** A theme and font-scale combination for [screenGallery]. */
enum class GalleryVariant(val suffix: String, val dark: Boolean, val fontScale: Float) {
    LIGHT("light", false, 1f),
    DARK("dark", true, 1f),
    /** 200 % font scale (the largest system setting), applied linearly: stricter than Android 14's non-linear scaling. */
    FONT_200("font200", false, 2f),
    ;

    companion object {
        val THEMES = listOf(LIGHT, DARK)
        val ALL = entries.toList()
    }
}

/**
 * Renders [content] once per [variants] (#71, #72): saves `build/outputs/gallery/<name>-<variant>.png` and, unless
 * [a11y] is false, runs [AccessibilityChecks] on each. [content] gets the variant (its theme is already applied).
 * Uses a single composition (setContent may only be called once per test) and switches the variant through state.
 */
@RequiresApi(Build.VERSION_CODES.O) // captureToImage; Robolectric runs these on API 34
fun ComposeContentTestRule.screenGallery(
    name: String,
    variants: List<GalleryVariant> = GalleryVariant.ALL,
    a11y: Boolean = true,
    allow: (AccessibilityChecks.Violation) -> Boolean = { false },
    content: @Composable (GalleryVariant) -> Unit,
) {
    var current by mutableStateOf(variants.first())
    setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, current.fontScale)) {
            OrangeBoxTheme(darkTheme = current.dark) { content(current) }
        }
    }
    for (v in variants) {
        current = v
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        saveGalleryShot("$name-${v.suffix}")
        if (a11y) AccessibilityChecks.assertNone(this, "$name (${v.suffix})", allow)
    }
}

/** Writes the first root (the screen, not a dialog window) to `build/outputs/gallery/<file>.png`. */
@RequiresApi(Build.VERSION_CODES.O)
fun ComposeContentTestRule.saveGalleryShot(file: String) {
    val bitmap = onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
    File("build/outputs/gallery/$file.png").apply { parentFile?.mkdirs() }.outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
