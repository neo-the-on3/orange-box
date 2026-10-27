package io.github.neotheon3.orangebox.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.tryResolve
import com.intellij.psi.PsiMethod

/**
 * #73: user-visible text in Compose code must come from string resources, so it can be translated. Android's own
 * `HardcodedText` only covers XML layouts. This flags a string literal (or constant) passed to a `text`,
 * `contentDescription`, `label`, `title`, `placeholder` or `onClickLabel` parameter of an androidx.compose function, and
 * `contentDescription` / `stateDescription` / `onClickLabel` set to a literal in a semantics block. Literals without
 * letters ("·", "%", "---") are allowed, and so are `@Preview` functions.
 */
class HardcodedComposeTextDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java, UBinaryExpression::class.java)

    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            if (!isCompose(method)) return
            val mapping = context.evaluator.computeArgumentMapping(node, method)
            for ((arg, param) in mapping) {
                if (param.name in TEXT_PARAMS && param.type.canonicalText == "java.lang.String") check(context, arg, "`${param.name}`")
            }
        }

        override fun visitBinaryExpression(node: UBinaryExpression) {
            if (node.operator != UastBinaryOperator.ASSIGN) return
            val target = node.leftOperand.tryResolve() as? PsiMethod ?: return
            val name = target.name.removePrefix("set").removePrefix("get").replaceFirstChar { it.lowercase() }
            if (name !in SEMANTICS_PROPERTIES) return
            if (target.containingClass?.qualifiedName?.startsWith("androidx.compose.ui.semantics") != true) return
            check(context, node.rightOperand, "`$name`")
        }
    }

    // Animation `label`s are debugging names for the inspector, never shown to the user.
    private fun isCompose(method: PsiMethod): Boolean {
        val owner = method.containingClass?.qualifiedName ?: return false
        return owner.startsWith("androidx.compose.") && !owner.startsWith("androidx.compose.animation")
    }

    private fun check(context: JavaContext, arg: UExpression, what: String) {
        val value = ConstantEvaluator.evaluate(context, arg) as? String ?: return
        if (value.none { it.isLetter() }) return
        if (inPreview(arg)) return
        context.report(
            ISSUE, arg, context.getLocation(arg),
            "Hard-coded text \"${value.take(40)}\" for $what: move it to `res/values/strings.xml` and use `stringResource`",
        )
    }

    private fun inPreview(element: UElement): Boolean {
        var m = element.getParentOfType<UMethod>(UMethod::class.java)
        while (m != null) {
            if (m.uAnnotations.any { it.qualifiedName?.endsWith(".Preview") == true || it.qualifiedName?.endsWith("Preview") == true }) return true
            m = m.getParentOfType(UMethod::class.java)
        }
        return false
    }

    companion object {
        private val TEXT_PARAMS = setOf("text", "contentDescription", "label", "title", "placeholder", "onClickLabel")
        private val SEMANTICS_PROPERTIES = setOf("contentDescription", "stateDescription", "onClickLabel", "paneTitle")

        val ISSUE: Issue = Issue.create(
            id = "HardcodedComposeText",
            briefDescription = "Hard-coded text in Compose UI",
            explanation = """
                User-visible text belongs in string resources so it can be translated (and pseudo-localised in \
                debug builds). Use `stringResource(R.string.…)` instead of a literal. Non-text literals (symbols, \
                separators) are allowed.
            """,
            category = Category.I18N,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(HardcodedComposeTextDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
