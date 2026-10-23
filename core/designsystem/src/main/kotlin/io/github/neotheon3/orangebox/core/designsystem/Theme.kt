package io.github.neotheon3.orangebox.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OrangeBoxColors {
    val Orange = Color(0xFFF57C00)
    val OrangeDark = Color(0xFFB34700)
    val OrangeLight = Color(0xFFFFB74D)
    val Charcoal = Color(0xFF1E1E1E)
    val Ok = Color(0xFF2E7D32)
    val Warn = Color(0xFFF9A825)
    val Error = Color(0xFFC62828)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = OrangeBoxColors.Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF755846),
    tertiary = Color(0xFF606135),
    error = OrangeBoxColors.Error,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = OrangeBoxColors.OrangeLight,
    onPrimary = Color(0xFF4C2700),
    primaryContainer = OrangeBoxColors.OrangeDark,
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE5BFA9),
    tertiary = Color(0xFFC9C893),
    background = OrangeBoxColors.Charcoal,
    surface = OrangeBoxColors.Charcoal,
)

/**
 * App theme. Dynamic color is intentionally not used so the brand orange stays
 * consistent across devices (and on API 24-30, which have no dynamic color).
 */
@Composable
fun OrangeBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
