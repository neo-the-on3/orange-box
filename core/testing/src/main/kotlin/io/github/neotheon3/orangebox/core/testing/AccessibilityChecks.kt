package io.github.neotheon3.orangebox.core.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import org.junit.Assert.fail

/**
 * Accessibility checks over the Compose semantics tree, for Robolectric tests (#71).
 *
 * Google's Accessibility Test Framework (`enableAccessibilityChecks`) needs a device on API 34+ and does not run under
 * Robolectric, so these cover the same ground with the semantics tree only:
 * - every clickable, toggleable or selectable node has a label TalkBack can read (text, content description, or a
 *   labelled child);
 * - its touch target is at least [minTouchDp] × [minTouchDp] (48 dp, Material and Android guidance), unless it is clipped
 *   by the window or a scroll container, where its full size can't be measured;
 * - nothing is announced as an empty string.
 * Colour contrast is checked on the palettes instead (ContrastTest), where it can be measured exactly.
 */
object AccessibilityChecks {
    data class Violation(val rule: String, val node: String) {
        override fun toString() = "$rule: $node"
    }

    fun violations(provider: SemanticsNodeInteractionsProvider, minTouchDp: Float = 48f): List<Violation> {
        val nodes = provider.onAllNodes(SemanticsMatcher("any node") { true }).fetchSemanticsNodes(atLeastOneRootRequired = true)
        val out = mutableListOf<Violation>()
        for (node in nodes) {
            val c = node.config
            if (c.getOrNull(SemanticsProperties.InvisibleToUser) != null) continue
            if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isBlank() } == true) {
                out += Violation("empty content description", describe(node))
            }
            if (!isActionable(node)) continue
            if (!hasLabel(node, depth = 3)) out += Violation("no label for an actionable node", describe(node))
            val density = node.layoutInfo.density.density
            val unclipped = node.boundsInRoot.width.toInt() >= node.size.width - 1 && node.boundsInRoot.height.toInt() >= node.size.height - 1
            if (unclipped) {
                val touch = node.touchBoundsInRoot
                val w = maxOf(touch.width, node.size.width.toFloat()) / density
                val h = maxOf(touch.height, node.size.height.toFloat()) / density
                if (w < minTouchDp - 0.5f || h < minTouchDp - 0.5f) {
                    out += Violation("touch target %.0f×%.0f dp < %.0f dp".format(w, h, minTouchDp), describe(node))
                }
            }
        }
        return out
    }

    /** Fails with every violation found; [allow] filters known, reviewed exceptions by a substring of the node. */
    fun assertNone(provider: SemanticsNodeInteractionsProvider, where: String, allow: (Violation) -> Boolean = { false }) {
        val found = violations(provider).filterNot(allow)
        if (found.isNotEmpty()) fail("Accessibility violations in $where:\n" + found.joinToString("\n") { " - $it" })
    }

    private fun isActionable(node: SemanticsNode): Boolean {
        val c = node.config
        return SemanticsActions.OnClick in c || SemanticsProperties.ToggleableState in c ||
            (SemanticsProperties.Selected in c && SemanticsActions.OnClick in c)
    }

    private fun hasLabel(node: SemanticsNode, depth: Int): Boolean {
        val c = node.config
        if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true) return true
        if (c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true) return true
        if (c.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true) return true
        if (c.getOrNull(SemanticsActions.OnClick)?.label?.isNotBlank() == true) return true
        return depth > 0 && node.children.any { hasLabel(it, depth - 1) }
    }

    private fun describe(node: SemanticsNode): String {
        val c = node.config
        val tag = c.getOrNull(SemanticsProperties.TestTag)
        val role = c.getOrNull(SemanticsProperties.Role)?.let { if (it == Role.Button) "Button" else it.toString() }
        val text = c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return listOfNotNull("#${node.id}", tag?.let { "tag=$it" }, role?.let { "role=$it" }, text?.let { "\"$it\"" }, "at ${node.boundsInRoot}")
            .joinToString(" ")
    }
}
