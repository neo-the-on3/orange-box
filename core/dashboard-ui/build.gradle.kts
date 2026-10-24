plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.android.compose)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.dashboardui"
}

dependencies {
    api(project(":core:telemetry"))
    api(project(":core:designsystem"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
