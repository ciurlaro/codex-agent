import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

val desktopManifest = rootProject.layout.projectDirectory.file(
    "codex-agent-runtime-desktop/codex-app-server-distributions.json",
)
val externalSupervisorIdentity = providers.gradleProperty(
    "codexAgent.windowsNodeSupervisorIdentityFile",
).map(::File)
val trackedSupervisorIdentity = layout.projectDirectory.file("windows-supervisor.json")
val selectedSupervisorIdentity = when {
    externalSupervisorIdentity.isPresent -> layout.file(externalSupervisorIdentity)
    trackedSupervisorIdentity.asFile.isFile -> providers.provider { trackedSupervisorIdentity }
    else -> null
}
val generateNodeDistributionSource = tasks.register<GenerateNodeDistributionSourceTask>(
    "generateNodeDistributionSource",
) {
    distributionManifest.set(desktopManifest)
    selectedSupervisorIdentity?.let(windowsSupervisorIdentity::set)
    outputDirectory.set(layout.buildDirectory.dir("generated/distributions/kotlin"))
}

kotlin {
    js(IR) {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        jsMain {
            kotlin.srcDir(generateNodeDistributionSource)
            dependencies {
                api(project(":codex-agent-client"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
            }
        }
        jsTest.dependencies { implementation(kotlin("test")) }
    }
}

rootProject.extensions.configure<NodeJsEnvSpec> { download.set(false) }
extensions.configure<NodeJsEnvSpec> { download.set(false) }

val supervisorSource = layout.projectDirectory.dir("src/windowsSupervisor")
val builtSupervisor = layout.buildDirectory.file(
    "windows-supervisor/$WINDOWS_SUPERVISOR_FILE_NAME",
)
val generatedSupervisorIdentity = layout.buildDirectory.file(
    "windows-supervisor/$WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME",
)
val buildWindowsSupervisor = tasks.register<BuildWindowsNodeSupervisorTask>(
    "buildWindowsNodeSupervisor",
) {
    sourceDirectory.set(supervisorSource)
    outputExecutable.set(builtSupervisor)
    generatedIdentityFile.set(generatedSupervisorIdentity)
}
val packageWindowsSupervisor = tasks.register<PackageWindowsNodeSupervisorTask>(
    "packageWindowsNodeSupervisor",
) {
    dependsOn(buildWindowsSupervisor)
    executableFile.set(builtSupervisor)
    generatedIdentityFile.set(generatedSupervisorIdentity)
    canonicalIdentityFile.set(selectedSupervisorIdentity ?: generatedSupervisorIdentity)
    sourceDirectory.set(supervisorSource)
    packageFile.set(layout.buildDirectory.file(
        "distributions/codex-agent-runtime-node-${project.version}-windows-supervisor.zip",
    ))
}
val externalSupervisorPackage = providers.gradleProperty(
    "codexAgent.windowsNodeSupervisorPackage",
).map(::File)
val selectedSupervisorPackage = if (externalSupervisorPackage.isPresent) {
    check(externalSupervisorIdentity.isPresent) {
        "A Windows supervisor package requires its canonical identity"
    }
    layout.file(externalSupervisorPackage)
} else {
    packageWindowsSupervisor.flatMap { it.packageFile }
}
val verifyWindowsSupervisor = tasks.register<VerifyWindowsNodeSupervisorPackageTask>(
    "verifyWindowsNodeSupervisorPackage",
) {
    if (!externalSupervisorPackage.isPresent) dependsOn(packageWindowsSupervisor)
    packageFile.set(selectedSupervisorPackage)
    canonicalIdentityFile.set(selectedSupervisorIdentity ?: generatedSupervisorIdentity)
    sourceDirectory.set(supervisorSource)
    proofFile.set(layout.buildDirectory.file("reports/windows-supervisor-proof.json"))
}

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

val nodeRuntimeEvidenceRunnerArchive = layout.file(
    providers.gradleProperty("codexAgent.nodeRuntimeEvidenceRunnerArchive").map(::File),
)
val nodeClassifierArchive = layout.file(
    providers.gradleProperty("codexAgent.nodeClassifierArchive").map(::File),
)
val nodeWindowsSupervisor = layout.file(
    providers.gradleProperty("codexAgent.nodeWindowsSupervisorExecutable").map(::File),
)
listOf("macosArm64", "macosX64", "linuxX64", "mingwX64").forEach { target ->
    tasks.register<RecordNodeRuntimeEvidenceTask>(
        "nodeRuntime${target.replaceFirstChar(Char::uppercase)}Test",
    ) {
        group = "verification"
        description = "Runs the exact $target Node App Server lifecycle evidence."
        candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
        this.target.set(target)
        runnerOs.set(providers.environmentVariable("RUNNER_OS"))
        runnerArch.set(providers.environmentVariable("RUNNER_ARCH"))
        nodeExecutable.set(providers.gradleProperty("codexAgent.nodeExecutable").orElse("node"))
        distributionManifest.set(desktopManifest)
        classifierArchive.set(nodeClassifierArchive)
        compiledNodeTestRuntime.set(nodeRuntimeEvidenceRunnerArchive)
        if (target == "mingwX64") {
            dependsOn(verifyWindowsSupervisor)
            windowsSupervisor.set(nodeWindowsSupervisor)
        }
        evidenceFile.set(layout.buildDirectory.file(
            "reports/node-runtime-evidence/${nodeRuntimeEvidenceFileName(target)}",
        ))
        testReport.set(layout.buildDirectory.file(
            "test-results/node-runtime-evidence/${nodeRuntimeTestReportFileName(target)}",
        ))
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    coordinates("io.github.ciurlaro", "codex-agent-runtime-node", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) signAllPublications()
    pom {
        name.set("Codex Agent Runtime for Node")
        description.set("Kotlin/JS Node process runtime for the Codex App Server.")
        inceptionYear.set("2026")
        url.set("https://github.com/ciurlaro/codex-agent")
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
            url.set("https://github.com/ciurlaro/codex-agent")
            connection.set("scm:git:https://github.com/ciurlaro/codex-agent.git")
            developerConnection.set("scm:git:ssh://git@github.com/ciurlaro/codex-agent.git")
        }
    }
}

extensions.configure<PublishingExtension> {
    publications.withType(MavenPublication::class.java).configureEach {
        if (name == "kotlinMultiplatform") {
            artifact(selectedSupervisorPackage) {
                classifier = "windows-supervisor-x64"
                extension = "zip"
                builtBy(verifyWindowsSupervisor)
            }
        }
    }
}

dependencyLocking { lockAllConfigurations() }
