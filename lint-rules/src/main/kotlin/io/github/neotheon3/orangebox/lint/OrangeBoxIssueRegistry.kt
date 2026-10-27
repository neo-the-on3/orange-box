package io.github.neotheon3.orangebox.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class OrangeBoxIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(HardcodedComposeTextDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val minApi: Int = 14
    override val vendor = Vendor(vendorName = "Orange Box", identifier = "orange-box", feedbackUrl = "https://github.com/neo-the-on3/orange-box/issues")
}
