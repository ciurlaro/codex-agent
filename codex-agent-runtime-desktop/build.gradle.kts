import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

val generateDesktopDistributionSource = tasks.register<GenerateDesktopDistributionSourceTask>(
    "generateDesktopDistributionSource",
) {
    manifestFile.set(layout.projectDirectory.file("codex-app-server-distributions.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/distributions/kotlin"))
}
val desktopManifest = readDesktopCodexManifest(
    layout.projectDirectory.file("codex-app-server-distributions.json").asFile,
)
val localArchiveDirectory = providers.gradleProperty("codexAgent.desktopArchiveDirectory")
val desktopPackageTasks = desktopManifest.distributions.associateWith { distribution ->
    tasks.register<PackageDesktopCodexRuntimeTask>(
        "package${distribution.target.replaceFirstChar(Char::uppercase)}AppServer",
    ) {
        group = "distribution"
        description = "Packages the verified ${distribution.target} Codex app server."
        releaseTag.set(desktopManifest.releaseTag)
        asset.set(distribution.asset)
        archiveSha256.set(distribution.archiveSha256)
        archiveEntry.set(distribution.archiveEntry)
        binarySha256.set(distribution.binarySha256)
        executableName.set(distribution.executableName)
        localArchive.set(layout.file(localArchiveDirectory.map { file("$it/${distribution.asset}") }))
        licenseFile.set(rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt",
        ))
        noticeFile.set(rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt",
        ))
        outputFile.set(layout.buildDirectory.file(
            "distributions/codex-agent-runtime-desktop-${project.version}-${distribution.classifier}.zip",
        ))
    }
}

tasks.register("packageDesktopAppServers") {
    group = "distribution"
    description = "Packages all five verified Codex desktop app-server classifiers."
    dependsOn(desktopPackageTasks.values)
}

tasks.matching { it.name in setOf("commonizeCInterop", "compileNativeMainKotlinMetadata") }.configureEach {
    notCompatibleWithConfigurationCache("Kotlin/Native commonization accesses project state at execution time")
}

kotlin {
    val desktopTargets = listOf(
        macosArm64(),
        macosX64(),
        linuxArm64(),
        linuxX64(),
        mingwX64(),
    )
    applyDefaultHierarchyTemplate()
    desktopTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.create("codexDesktop") {
            defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/codex_desktop.def"))
            includeDirs(layout.projectDirectory.dir("native/include"))
        }
    }

    sourceSets {
        val nativeMain by getting {
            kotlin.srcDir(generateDesktopDistributionSource)
            dependencies {
                api(project(":codex-agent-client"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
            }
        }
        val nativeTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

check(desktopManifest.distributions.map { it.target }.toSet() == desktopRuntimeEvidenceTargets.keys) {
    "Desktop evidence target set does not match the distribution manifest"
}
val requestedEvidenceTarget = providers.gradleProperty("codexAgent.desktopEvidenceTarget").orNull
requestedEvidenceTarget?.let { check(it in desktopRuntimeEvidenceTargets) { "Unknown desktop evidence target: $it" } }
desktopManifest.distributions.forEach { distribution ->
    val packageTask = desktopPackageTasks.getValue(distribution)
    val validateEvidenceTarget = tasks.register(
        "validate${distribution.target.replaceFirstChar(Char::uppercase)}DesktopEvidenceTarget",
    ) {
        inputs.property(
            "requestedTarget",
            providers.gradleProperty("codexAgent.desktopEvidenceTarget").orElse(""),
        )
        inputs.property("expectedTarget", distribution.target)
        doLast {
            val requested = inputs.properties.getValue("requestedTarget")
            val expected = inputs.properties.getValue("expectedTarget")
            check(requested == expected) {
                "-PcodexAgent.desktopEvidenceTarget must equal $expected"
            }
        }
    }
    val extractedExecutable = layout.buildDirectory.file(
        "desktop-runtime-evidence/${distribution.target}/${distribution.executableName}",
    )
    val extractTask = tasks.register<ExtractDesktopAppServerTask>(
        "extract${distribution.target.replaceFirstChar(Char::uppercase)}AppServerForSmoke",
    ) {
        archiveFile.set(packageTask.flatMap { it.outputFile })
        executableName.set(distribution.executableName)
        binarySha256.set(distribution.binarySha256)
        outputFile.set(extractedExecutable)
    }
    val testTaskName = "${distribution.target}Test"
    if (requestedEvidenceTarget == distribution.target) {
        tasks.named<KotlinNativeTest>(testTaskName) {
            dependsOn(extractTask)
            environment("CODEX_AGENT_APP_SERVER_EXECUTABLE", extractedExecutable.get().asFile.absolutePath)
            outputs.upToDateWhen { false }
        }
    }
    tasks.matching { it.name == testTaskName }.configureEach { mustRunAfter(validateEvidenceTarget) }
    tasks.register<RecordDesktopRuntimeEvidenceTask>(
        "record${distribution.target.replaceFirstChar(Char::uppercase)}DesktopRuntimeEvidence",
    ) {
        group = "verification"
        description = "Runs and records the ${distribution.target} official app-server lifecycle smoke."
        dependsOn(validateEvidenceTarget, testTaskName)
        target.set(distribution.target)
        classifier.set(distribution.classifier)
        binarySha256.set(distribution.binarySha256)
        candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
        runnerOs.set(providers.environmentVariable("RUNNER_OS"))
        runnerArch.set(providers.environmentVariable("RUNNER_ARCH"))
        classifierArchive.set(packageTask.flatMap { it.outputFile })
        testReport.set(layout.buildDirectory.file(
            "test-results/${distribution.target}Test/" +
                "TEST-${distribution.target}Test.io.github.ciurlaro.codexmobile.appserver.runtime." +
                "DesktopCodexRuntimeTest.xml",
        ))
        evidenceFile.set(layout.buildDirectory.file(
            "reports/desktop-runtime-evidence/${desktopRuntimeEvidenceFileName(distribution.target)}",
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
    coordinates("io.github.ciurlaro", "codex-agent-runtime-desktop", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
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
            desktopPackageTasks.forEach { (distribution, packageTask) ->
                artifact(packageTask.flatMap { it.outputFile }) {
                    classifier = distribution.classifier
                    extension = "zip"
                    builtBy(packageTask)
                }
            }
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}
