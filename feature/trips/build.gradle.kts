plugins {
    alias(libs.plugins.orangebox.android.feature)
}

android {
    namespace = "io.github.neotheon3.orangebox.feature.trips"
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:common"))
}
