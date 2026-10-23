plugins {
    alias(libs.plugins.orangebox.jvm.library)
}

dependencies {
    api(project(":core:obd"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:simulator"))
}
