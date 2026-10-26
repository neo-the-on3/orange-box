plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.datastore"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))
    api(libs.androidx.datastore.preferences)
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
