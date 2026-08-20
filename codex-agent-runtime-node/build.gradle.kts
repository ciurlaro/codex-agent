import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    id("codexagent.node-runtime")
}

val codexAgentRepositoryUrl = rootProject.extra["codexAgent.repositoryUrl"].toString()

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()
    js(IR) {
        nodejs()
        binaries.executable()
    }
    wasmJs {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        val webMain by getting {
            kotlin.srcDir(tasks.named("generateNodeDistributionSource"))
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("runtime-host-shared/src/commonMain/kotlin"))
            dependencies {
                api(project(":codex-agent-client"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
            }
        }
        jsTest.dependencies { implementation(kotlin("test")) }
        wasmJsTest.dependencies { implementation(kotlin("test")) }
    }
}

rootProject.extensions.configure<NodeJsEnvSpec> { download.set(false) }
extensions.configure<NodeJsEnvSpec> { download.set(false) }
rootProject.extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }

val packageNodeRuntimeEvidenceRunner = tasks.register<Zip>(
    "packageNodeRuntimeEvidenceRunner",
) {
    group = "distribution"
    description = "Packages the compiled standalone Node runtime evidence runner."
    dependsOn("jsProductionExecutableCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")) {
        include("*.js")
    }
    archiveFileName.set("codex-agent-node-runtime-evidence-runner.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.STORED
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val members = zip.entries().asSequence().toList()
            check(members.isNotEmpty() && members.none { it.isDirectory } &&
                members.map { it.name }.toSet().size == members.size &&
                members.all { it.name == File(it.name).name && it.name.endsWith(".js") && it.size > 0 } &&
                members.any { it.name == "codex-agent-codex-agent-runtime-node.js" }) {
                "Node evidence runner package has an incomplete or unsafe CommonJS module set"
            }
        }
    }
}

val nodeWasmRunnerBaseName = "codex-agent-codex-agent-runtime-node"
val nodeWasmRunnerMembers = setOf(
    "$nodeWasmRunnerBaseName.mjs",
    "$nodeWasmRunnerBaseName.uninstantiated.mjs",
    "$nodeWasmRunnerBaseName.wasm",
    "custom-formatters.js",
)
val packageNodeWasmRuntimeEvidenceRunner = tasks.register<Zip>(
    "packageNodeWasmRuntimeEvidenceRunner",
) {
    group = "distribution"
    description = "Packages the unoptimized standalone Kotlin/Wasm Node evidence runner."
    dependsOn("wasmJsDevelopmentExecutableCompileSync")
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin")) {
        include(nodeWasmRunnerMembers)
    }
    archiveFileName.set("codex-agent-node-wasm-runtime-evidence-runner.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    entryCompression = ZipEntryCompression.STORED
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val members = zip.entries().asSequence().toList()
            val expectedMembers = setOf(
                "codex-agent-codex-agent-runtime-node.mjs",
                "codex-agent-codex-agent-runtime-node.uninstantiated.mjs",
                "codex-agent-codex-agent-runtime-node.wasm",
                "custom-formatters.js",
            )
            check(
                members.none { it.isDirectory } &&
                    members.map { it.name }.toSet() == expectedMembers &&
                    members.all { it.name == File(it.name).name && it.size > 0 }
            ) { "Node Wasm evidence runner package has an incomplete or unsafe module set" }
        }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    coordinates(project.group.toString(), "codex-agent-runtime-node", project.version.toString())
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) signAllPublications()
    pom {
        name.set("Codex Agent Runtime for Node")
        description.set("Kotlin/JS and Kotlin/Wasm Node process runtime for the Codex App Server.")
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

dependencyLocking { lockAllConfigurations() }
