plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.bluetooth"
}

dependencies {
    api(project(":core:obd"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
