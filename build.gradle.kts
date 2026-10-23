// Top-level build file. Module plugins are declared here with `apply false` so that
// every module resolves the same versions from gradle/libs.versions.toml.
buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin with a runtime dependency on an older KGP;
        // pin the KGP we actually want (see "Migrate to built-in Kotlin").
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
