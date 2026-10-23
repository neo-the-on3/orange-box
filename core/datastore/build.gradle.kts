plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.datastore"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.datastore.preferences)
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
