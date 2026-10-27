package io.github.neotheon3.orangebox.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class HardcodedComposeTextDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = HardcodedComposeTextDetector()
    override fun getIssues(): List<Issue> = listOf(HardcodedComposeTextDetector.ISSUE)

    private val stubs = arrayOf(
        kotlin(
            """
            package androidx.compose.material3
            fun Text(text: String, color: Long = 0) {}
            fun Icon(icon: Any, contentDescription: String?) {}
            """,
        ).indented(),
        kotlin(
            """
            package androidx.compose.ui.semantics
            interface SemanticsPropertyReceiver
            var SemanticsPropertyReceiver.contentDescription: String
                get() = ""
                set(value) {}
            fun semantics(block: SemanticsPropertyReceiver.() -> Unit) {}
            """,
        ).indented(),
        kotlin(
            """
            package androidx.compose.ui.tooling.preview
            annotation class Preview
            """,
        ).indented(),
    )

    fun `test literals are flagged`() {
        lint().files(
            *stubs,
            kotlin(
                """
                package test
                import androidx.compose.material3.Text
                import androidx.compose.material3.Icon
                import androidx.compose.ui.semantics.semantics
                import androidx.compose.ui.semantics.contentDescription
                fun Screen() {
                    Text("Save trip")
                    Icon(Any(), contentDescription = "Settings")
                    semantics { contentDescription = "Speed gauge" }
                }
                """,
            ).indented(),
        ).allowMissingSdk().run().expectErrorCount(3).expectContains("Hard-coded text \"Save trip\" for text: move it")
    }

    fun `test resources, symbols and previews are fine`() {
        lint().files(
            *stubs,
            kotlin(
                """
                package test
                import androidx.compose.material3.Text
                import androidx.compose.ui.tooling.preview.Preview
                fun label(): String = "x"
                fun Screen(name: String) {
                    Text(name)
                    Text("·")
                    Text("---")
                    Text(label() + " " + name)
                }
                @Preview fun ScreenPreview() { Text("Preview only") }
                """,
            ).indented(),
        ).allowMissingSdk().run().expectClean()
    }
}
