plugins {
    alias(libs.plugins.orangebox.jvm.library)
}

// Backend sync rules (M9): pull walks, deletions, sync-window recovery and uploads, over core:network's
// BackendApi and a SyncStore for the local side. Pure JVM so the rules are tested without Android.
dependencies {
    api(project(":core:network"))

    testImplementation(libs.kotlinx.coroutines.test)
}
