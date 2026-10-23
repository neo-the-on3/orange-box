plugins {
    alias(libs.plugins.orangebox.jvm.library)
}

dependencies {
    api(project(":core:obd"))
    implementation(project(":core:common"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:simulator"))
}
