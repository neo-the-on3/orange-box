plugins {
    alias(libs.plugins.orangebox.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Debug-only dependency of :app (debugImplementation), so none of this reaches release builds.
dependencies {
    api(project(":core:obd"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
