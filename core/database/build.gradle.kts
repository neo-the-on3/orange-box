plugins {
    alias(libs.plugins.orangebox.android.library)
    alias(libs.plugins.orangebox.hilt)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.neotheon3.orangebox.core.database"
}

// Exported schemas double as unit-test assets for MigrationTestHelper.
extensions.getByType<com.android.build.api.dsl.LibraryExtension>().sourceSets.getByName("test")
    .assets.directories.add("$projectDir/schemas")

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:sync"))
    implementation(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.room.testing)
}
