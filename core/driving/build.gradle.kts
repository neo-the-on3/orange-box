plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.driving"
}

dependencies {
    api(project(":core:obd"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:testing"))
}
