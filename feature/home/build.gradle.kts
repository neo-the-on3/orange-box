plugins {
    alias(libs.plugins.orangebox.android.feature)
}

android {
    namespace = "io.github.neotheon3.orangebox.feature.home"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:obd"))
    implementation(project(":core:telemetry"))
}
