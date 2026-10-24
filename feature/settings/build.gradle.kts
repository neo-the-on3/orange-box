plugins {
    alias(libs.plugins.orangebox.android.feature)
}

android {
    namespace = "io.github.neotheon3.orangebox.feature.settings"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:driving"))
}
