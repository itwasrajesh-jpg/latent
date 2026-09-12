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
rootProject.name = "Latent"
include(":app")

// Fetched by the build workflow from the pinned spektrafilm mirror. GPLv3 — see NOTICE.md.
include(":engine:spektra-core")
include(":lib:libraw")
