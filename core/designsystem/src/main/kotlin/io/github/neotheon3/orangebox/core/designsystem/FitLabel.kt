package io.github.neotheon3.orangebox.core.designsystem

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A one-line label for tight slots (tabs, segmented buttons): it shrinks to fit instead of wrapping mid-word or
 * spilling out of the button at large font scales (#71). The floor is 10 dp, so it stays readable even at 200 %.
 */
@Composable
fun FitLabel(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val max = if (style.fontSize.isSpecified) style.fontSize else 14.sp
    val min = with(LocalDensity.current) { 10.dp.toSp() }
    Text(
        text,
        modifier = modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        autoSize = if (max.value > min.value) TextAutoSize.StepBased(minFontSize = min, maxFontSize = max, stepSize = 0.5.sp) else null,
    )
}

/** Largest font scale a [SegmentLabel] follows: segmented buttons have a fixed height and share the row's width. */
const val MAX_SEGMENT_FONT_SCALE = 1.5f

/**
 * A segmented-button label (#71). The row sizes its buttons with intrinsic measurements, so an auto-sized label would
 * shrink to the floor; instead the label follows the font scale up to [MAX_SEGMENT_FONT_SCALE] (it is already a
 * large-text control at that size) and ellipsizes rather than spilling over the button outline.
 */
@Composable
fun SegmentLabel(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(MAX_SEGMENT_FONT_SCALE))) {
        Text(text, modifier = modifier, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
