import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    id("codexagent.desktop-runtime")
}

val codexAgentRepositoryUrl = rootProject.extra["codexAgent.repositoryUrl"].toString()

kotlin {
    explicitApi()
    sourceSets {
        commonMain { kotlin.srcDir(rootProject.layout.projectDirectory.dir("runtime-host-shared/src/commonMain/kotlin")) }
        commonMain.dependencies {
            api(project(":codex-agent-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        nativeTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    coordinates(project.group.toString(), "codex-agent-runtime-desktop", project.version.toString())
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("Codex Agent Runtime for Desktop")
        description.set("Native desktop process runtime for the Codex App Server.")
        inceptionYear.set("2026")
        url.set(codexAgentRepositoryUrl)
        licenses {
            license {
                name.set("GNU General Public License v3.0 or later")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ciurlaro")
                name.set("Cesare Iurlaro")
                url.set("https://github.com/ciurlaro")
            }
        }
        scm {
            url.set(codexAgentRepositoryUrl)
            connection.set("scm:git:$codexAgentRepositoryUrl.git")
            developerConnection.set("scm:git:ssh://git@github.com/${codexAgentRepositoryUrl.substringAfter("github.com/")}.git")
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}
