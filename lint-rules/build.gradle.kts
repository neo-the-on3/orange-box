import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Custom lint checks (#73), run by every Android module through `lintChecks(project(":lint-rules"))`.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("com.android.lint")
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    // Lint loads this jar next to its own (older) Kotlin runtime: stay on language/API features it has.
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}

// `./gradlew testDebugUnitTest` (the local and CI command) runs these tests too.
tasks.register("testDebugUnitTest") { dependsOn("test") }
