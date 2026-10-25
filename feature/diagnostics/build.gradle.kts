plugins {
    alias(libs.plugins.orangebox.android.feature)
}

android {
    namespace = "io.github.neotheon3.orangebox.feature.diagnostics"
}

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":core:dashboard-ui"))
    implementation(project(":core:obd"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:common"))

    testImplementation(project(":core:testing"))
    testImplementation(project(":core:simulator"))
}
