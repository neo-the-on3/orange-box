plugins {
    alias(libs.plugins.orangebox.android.feature)
}

// Remote-only member screens (#68): server stats summary, rankings and points, Material 3 defaults. Shown in Fake
// mode too (demo and tests), never in Off.
android {
    namespace = "io.github.neotheon3.orangebox.feature.rankings"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:common"))
}
