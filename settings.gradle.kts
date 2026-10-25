pluginManagement {
    repositories {
        google()
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
rootProject.name = "swiss-tv-launcher"

include(
    ":app",
    ":core:design", ":core:focus", ":core:perf",
    ":domain",
    ":feature:home", ":feature:apps", ":feature:search", ":feature:title",
    ":feature:panels", ":feature:settings", ":feature:screensaver",
    ":data",
    ":platform:tv",
    ":benchmark",
)
