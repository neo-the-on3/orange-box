plugins {
    alias(libs.plugins.orangebox.android.application)
    alias(libs.plugins.orangebox.android.compose)
    alias(libs.plugins.orangebox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Build identity. versionCode is the commit count, so a CI APK and a local APK built from the same
// commit share it and can be installed over each other (CI checks out with full history).
// versionName = <base>.<count>-<sha>[-ci<run>], plus "-debug" on debug builds.
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val baseVersion = "0.1.0"
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitSha = git("rev-parse", "--short=7", "HEAD") ?: "nogit"
val ciRun: String? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull

android {
    namespace = "io.github.neotheon3.orangebox"

    defaultConfig {
        applicationId = "io.github.neotheon3.orangebox"
        versionCode = commitCount
        versionName = "$baseVersion.$commitCount-$gitSha" + (ciRun?.let { "-ci$it" } ?: "")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "CI_RUN", ciRun?.let { "\"$it\"" } ?: "null")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // Shared debug key (committed on purpose, not a secret): CI and local debug builds get the same
        // signature, so testers can install one over the other. Debug build type only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned in CI. Signing is configured later from CI secrets, never committed.
        }
    }

    buildFeatures {
        buildConfig = true
    }
    lint {
        // Keep lint strict for errors; warnings are reported but do not fail the build.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:trips"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:driving"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:driving"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(project(":core:obd"))
    implementation(project(":core:bluetooth"))
    implementation(project(":core:telemetry"))
    // Simulated ELM327 + Simulator screen: debug only, never on the release classpath.
    debugImplementation(project(":core:simulator"))
    debugImplementation(libs.androidx.hilt.navigation.compose)
    debugImplementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// AGP 9 only creates host (unit) tests for the debug build type by default. Enable them for
// release too so a test can prove the release build ignores the backend_mode extra.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}
