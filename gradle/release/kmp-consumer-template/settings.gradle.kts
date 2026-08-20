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
        val staging = providers.gradleProperty("CENTRAL_STAGING").get()
        exclusiveContent {
            forRepository {
                maven {
                    name = "CENTRAL_STAGING"
                    url = uri(staging)
                }
            }
            filter { includeGroup("io.github.codex-agent-labs") }
        }
        google { content { excludeGroup("io.github.codex-agent-labs") } }
        mavenCentral { content { excludeGroup("io.github.codex-agent-labs") } }
    }
}

rootProject.name = "codex-agent-staged-consumer"
