package io.github.neotheon3.orangebox.core.dashboardui

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/**
 * Dashboard palette, measured from the legacy layouts (docs/ui/LEGACY_OBD_UI.md §3). Only the
 * numbers are reused; every shape is drawn by Orange Box code.
 */
object DashboardColors {
    val Background = Color(0xFF000000)
    val Arch = Color(0xFF232323)
    val ArchRim = Color(0xFF545454)

    val BezelTop = Color(0xFF4A4A4A)
    val BezelBottom = Color(0xFF242424)
    val BezelBand = Color(0xFF0A0A0A)
    val ScreenStrokeTop = Color(0xFFF1F1F1)
    val ScreenStrokeBottom = Color(0xFF969696)
    val ScreenTop = Color(0xFF494949)
    val ScreenBottom = Color(0xFF000000)

    val CardFill = Color(0xFF1A1A1A)
    val CardRim = Color(0xFF393939)
    val TileFill = Color(0xFF1B1B1B)
    val TileBorder = Color(0xFF3A3A3A)
    val TileTitle = Color(0xFFCCCCCC)
    val Text = Color(0xFFFFFFFF)
    val Caption = Color(0xFFE6E6E6)

    val LampOff = Color(0xFF686868)
    val Mil = Color(0xFFF80028)
    val Obd = Color(0xFF80D8F0)
    val FuelCut = Color(0xFF18F800)
    val Overspeed = Color(0xFFF80000)
    val Event = Color(0xFFF89018)
    val EcoGreen = Color(0xFF10C800)
    val EcoYellow = Color(0xFFF8E000)
    val EcoRed = Color(0xFFF80000)
    val Normal = Color(0xFF00A0E0)
    val Warning = Color(0xFFF89018)
    val TileWarningIcon = Color(0xFFE00000)
    val DetailButtonOn = Color(0xFF0448C0)
    val Accent = Color(0xFF33B5E5)
    val PageDotOff = Color(0xFF8A8A8A)

    val FuelTrack = Color(0xFF1E253B)
    val FuelFill = Color(0xFF819FF7)

    val HazardRed = Color(0xFFE5262B)
}

/** Text sizes in sp (they follow the user's font scale; meters additionally auto-fit). */
object DashboardType {
    val MeterMax = 75.sp
    val MeterMin = 28.sp
    val MeterUnit = 18.sp
    val Value = 20.sp
    val ValueUnit = 12.sp
    val Header = 14.sp
    val Caption = 10.sp
    val TileTitle = 12.sp
    val TileValue = 22.sp
    val TileUnit = 12.sp
}

@Immutable
data class DashboardSettings(
    /** True when the system "Remove animations" setting is on: glows and page changes snap. */
    val reduceMotion: Boolean = false,
    /** No gloss or dot grid and stronger strokes. */
    val highContrast: Boolean = false,
)

val LocalDashboardSettings = staticCompositionLocalOf { DashboardSettings() }

/** Animator duration scale 0 = "Remove animations" in accessibility / developer options. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Always dark (decision D1), whatever the system theme. */
@Composable
fun DashboardTheme(
    settings: DashboardSettings = DashboardSettings(reduceMotion = rememberReduceMotion()),
    content: @Composable () -> Unit,
) {
    val scheme = darkColorScheme(
        background = DashboardColors.Background,
        surface = DashboardColors.CardFill,
        onBackground = DashboardColors.Text,
        onSurface = DashboardColors.Text,
        primary = DashboardColors.Accent,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalDashboardSettings provides settings, content = content)
    }
}
