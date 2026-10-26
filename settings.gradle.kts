pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "orange-box"

include(":app")
include(":core:model")
include(":core:common")
include(":core:obd")
include(":core:simulator")
include(":core:telemetry")
include(":core:bluetooth")
include(":core:testing")
include(":core:datastore")
include(":core:designsystem")
include(":core:database")
include(":core:dashboard-ui")
include(":core:driving")
include(":core:diagnostics")
include(":core:network")
include(":core:sync")
include(":feature:driving")
include(":feature:setup")
include(":feature:home")
include(":feature:settings")
include(":feature:trips")
include(":feature:diagnostics")
