plugins {
    alias(libs.plugins.orangebox.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)

    // FakeObdTransport lives in testFixtures and is re-exported by :core:testing.
    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
