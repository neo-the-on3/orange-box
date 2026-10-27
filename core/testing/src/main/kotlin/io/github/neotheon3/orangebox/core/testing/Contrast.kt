package io.github.neotheon3.orangebox.core.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** WCAG 2.x contrast ratio of [fg] over [bg] (1–21). A translucent [fg] is composited over [bg] first. */
fun contrast(fg: Color, bg: Color): Double {
    val f = fg.compositeOver(bg).luminance().toDouble()
    val b = bg.luminance().toDouble()
    return (maxOf(f, b) + 0.05) / (minOf(f, b) + 0.05)
}

/** WCAG AA: 4.5:1 for body text, 3:1 for large text (≥ 18 sp, or 14 sp bold) and for icons and other UI parts. */
object Wcag {
    const val TEXT = 4.5
    const val LARGE_TEXT_OR_UI = 3.0
}
