plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.driving"
}

dependencies {
    api(project(":core:obd"))
    api(project(":core:telemetry"))
    api(project(":core:database"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:testing"))
}
