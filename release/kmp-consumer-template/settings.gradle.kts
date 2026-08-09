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
            filter { includeGroup("io.github.ciurlaro") }
        }
        google { content { excludeGroup("io.github.ciurlaro") } }
        mavenCentral { content { excludeGroup("io.github.ciurlaro") } }
    }
}

rootProject.name = "codex-agent-staged-consumer"
