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

rootProject.name = "codex-agent"

include(
    ":codex-agent-client",
    ":codex-agent-runtime-android",
    ":codex-agent-runtime-desktop",
    ":codex-agent-runtime-ios",
    ":tooling:protocol-generator",
)
