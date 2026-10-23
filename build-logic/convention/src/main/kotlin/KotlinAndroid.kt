import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Shared Android + Kotlin settings for every Android module. */
internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    val jvm = libs.version("jvmTarget").toInt()
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig.minSdk = libs.version("minSdk").toInt()
        compileOptions.sourceCompatibility = JavaVersion.toVersion(jvm)
        compileOptions.targetCompatibility = JavaVersion.toVersion(jvm)
        testOptions.unitTests.isIncludeAndroidResources = true
    }
    extensions.configure<KotlinBaseExtension> { jvmToolchain(jvm) }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(libs.version("jvmTarget").toInt()) }
}
