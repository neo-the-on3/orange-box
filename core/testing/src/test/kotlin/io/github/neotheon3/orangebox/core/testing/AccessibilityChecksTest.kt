package io.github.neotheon3.orangebox.core.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityChecksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `labelled Material controls pass`() {
        compose.setContent {
            Column {
                Button(onClick = {}) { Text("Save") }
                IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                Checkbox(checked = true, onCheckedChange = {}, modifier = Modifier.semantics { contentDescription = "Accept" })
            }
        }
        assertEquals(emptyList<AccessibilityChecks.Violation>(), AccessibilityChecks.violations(compose))
    }

    @Test fun `a small unlabelled clickable and an empty description are reported`() {
        compose.setContent {
            Column {
                androidx.compose.foundation.layout.Box(Modifier.size(24.dp).background(Color.Gray).clickable {})
                IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = "") }
            }
        }
        val rules = AccessibilityChecks.violations(compose).map { it.rule }
        // Compose extends a small clickable's touch area to 48 dp; a stricter minimum still catches it.
        assertTrue(rules.toString(), rules.none { it.startsWith("touch target") })
        assertTrue(AccessibilityChecks.violations(compose, minTouchDp = 56f).any { it.rule.startsWith("touch target 48×48") })
        assertTrue(rules.toString(), rules.count { it == "no label for an actionable node" } == 2)
        assertTrue(rules.toString(), "empty content description" in rules)
    }

    @Test fun `contrast matches WCAG examples`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.01)
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.02)
    }
}
