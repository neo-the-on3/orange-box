import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin/JVM module (no Android). Lint still analyses it. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.android.lint")
        configureKotlinJvm()
        // Let `./gradlew testDebugUnitTest` also run plain JVM module tests.
        tasks.register("testDebugUnitTest") { dependsOn("test") }
        dependencies {
            add("testImplementation", libs.lib("junit"))
        }
    }
}
