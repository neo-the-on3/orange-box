package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.ui.graphics.Color

/**
 * State-driven panel glow. [stroke] is the screen rim colour and [tint] the inner wash; NONE keeps the
 * neutral bezel. Eco tints are the legacy values (§3); fuel cut, overspeed and harsh are Orange Box's.
 */
enum class PanelGlow(val stroke: Color?, val tint: Color?) {
    NONE(null, null),
    ECO_GREEN(Color(0xFFB6FFBD), Color(0xFF1F611F)),
    ECO_YELLOW(Color(0xFFFFE96E), Color(0xFF77691F)),
    ECO_RED(Color(0xFFFF8686), Color(0xFF621F1F)),
    FUEL_CUT(Color(0xFF6CFF5E), Color(0xFF1F611F)),
    OVERSPEED(Color(0xFFFF5050), Color(0xFF6E1414)),
    HARSH(Color(0xFFFFB45A), Color(0xFF6B3D12)),
}
