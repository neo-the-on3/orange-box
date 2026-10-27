plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.android.compose)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:datastore"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.robolectric)
    api(libs.androidx.test.core)
    // Screen gallery + accessibility checks for Robolectric Compose tests (M10, #71/#72).
    api(project(":core:designsystem"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui.test.junit4)
}

dependencies {
    api(testFixtures(project(":core:obd")))
}

dependencies {
    api(project(":core:obd"))
}

dependencies {
    api(project(":core:telemetry"))
}

dependencies {
    api(project(":core:database"))
}
