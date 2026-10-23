plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.android.compose)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.designsystem"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.core)
    api(libs.compose.ui.tooling.preview)

    testImplementation(project(":core:testing"))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
