plugins {
    alias(libs.plugins.orangebox.android.library)
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
}

dependencies {
    api(testFixtures(project(":core:obd")))
}

dependencies {
    api(project(":core:obd"))
}
