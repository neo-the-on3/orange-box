plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.diagnostics"
}

dependencies {
    api(project(":core:obd"))
    api(project(":core:database"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:simulator"))
}
