pluginManagement {
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

rootProject.name = "LumiPage"

include(":app")
include(":core:model")
include(":core:common")
include(":core:imaging")
include(":core:data")
include(":core:pdf")
include(":feature:scanner")
include(":feature:editor")
include(":feature:documents")
include(":feature:pdf")
