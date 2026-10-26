plugins {
    alias(libs.plugins.orangebox.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// REST v1 client for the optional backend (M9, docs/backend/openapi-v2.yaml). Pure JVM: OkHttp +
// kotlinx-serialization, no Retrofit and no Google services. Only built when the backend mode is Remote.
dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
