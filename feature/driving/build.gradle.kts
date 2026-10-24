plugins {
    alias(libs.plugins.orangebox.android.feature)
}

android {
    namespace = "io.github.neotheon3.orangebox.feature.driving"
}

dependencies {
    implementation(project(":core:dashboard-ui"))
    implementation(project(":core:driving"))
    implementation(project(":core:obd"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))

    testImplementation(project(":core:testing"))
}
