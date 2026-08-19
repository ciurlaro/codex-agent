import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

val generateDesktopDistributionSource = tasks.register<GenerateDesktopDistributionSourceTask>(
    "generateDesktopDistributionSource",
) {
    manifestFile.set(layout.projectDirectory.file("codex-app-server-distributions.json"))
    libraryVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/distributions/kotlin"))
}
val desktopManifest = readDesktopCodexManifest(
    layout.projectDirectory.file("codex-app-server-distributions.json").asFile,
)
val localArchiveDirectory = providers.gradleProperty("codexAgent.desktopArchiveDirectory")
val importedClassifierDirectory = providers.gradleProperty("codexAgent.desktopClassifierDirectory")
val supervisorDirectory = providers.gradleProperty("codexAgent.desktopSupervisorDirectory")
    .map(::file)
    .orElse(layout.buildDirectory.dir("supervisor").map { it.asFile })
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val hostTarget = when {
    "mac" in hostOs && hostArch in setOf("aarch64", "arm64") -> "macosArm64"
    "mac" in hostOs && hostArch in setOf("amd64", "x86_64") -> "macosX64"
    "linux" in hostOs && hostArch in setOf("aarch64", "arm64") -> "linuxArm64"
    "linux" in hostOs && hostArch in setOf("amd64", "x86_64") -> "linuxX64"
    "windows" in hostOs && hostArch in setOf("amd64", "x86_64") -> "mingwX64"
    else -> null
}
val hostSupervisorName = if (hostTarget == "mingwX64") {
    "codex-process-supervisor.exe"
} else {
    "codex-process-supervisor"
}
val compileDesktopProcessSupervisor = tasks.register<CompileDesktopProcessSupervisorTask>(
    "compileDesktopProcessSupervisor",
) {
    group = "distribution"
    description = "Compiles the process supervisor for the current desktop host."
    sourceFile.set(layout.projectDirectory.file("native/supervisor/codex_process_supervisor.c"))
    compiler.set(providers.gradleProperty("codexAgent.desktopSupervisorCompiler")
        .orElse(if (hostTarget == "mingwX64") "cl" else "cc"))
    windows.set(hostTarget == "mingwX64")
    outputFile.set(layout.buildDirectory.file("supervisor/${hostTarget ?: "unsupported"}/$hostSupervisorName"))
    enabled = hostTarget != null
}
val desktopPackageTasks = desktopManifest.distributions.associateWith { distribution ->
    tasks.register<PackageDesktopCodexRuntimeTask>(
        "package${distribution.target.replaceFirstChar(Char::uppercase)}AppServer",
    ) {
        group = "distribution"
        description = "Packages the verified ${distribution.target} Codex app server."
        releaseTag.set(desktopManifest.releaseTag)
        libraryVersion.set(project.version.toString())
        appServerVersion.set(desktopManifest.version)
        target.set(distribution.target)
        classifier.set(distribution.classifier)
        asset.set(distribution.asset)
        archiveSha256.set(distribution.archiveSha256)
        archiveEntry.set(distribution.archiveEntry)
        binarySha256.set(distribution.binarySha256)
        executableName.set(distribution.executableName)
        supervisorExecutableName.set(distribution.supervisorExecutableName)
        if (!importedClassifierDirectory.isPresent ||
            providers.gradleProperty("codexAgent.desktopSupervisorDirectory").isPresent) {
            supervisorExecutable.set(layout.file(supervisorDirectory.map { directory ->
                directory.resolve(distribution.target).resolve(distribution.supervisorExecutableName)
            }))
        }
        prebuiltPackage.set(layout.file(importedClassifierDirectory.map { directory ->
            file("$directory/codex-agent-runtime-desktop-${project.version}-${distribution.classifier}.zip")
        }))
        if (!importedClassifierDirectory.isPresent &&
            !providers.gradleProperty("codexAgent.desktopSupervisorDirectory").isPresent &&
            distribution.target == hostTarget) {
            dependsOn(compileDesktopProcessSupervisor)
        }
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
extensions.configure<KotlinMultiplatformExtension> {
    jvmToolchain(17)
    jvm()
    val desktopTargets = listOf(macosArm64(), macosX64(), linuxArm64(), linuxX64(), mingwX64())
    applyDefaultHierarchyTemplate()
    desktopTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.create("codexDesktop") {
            defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/codex_desktop.def"))
            includeDirs(layout.projectDirectory.dir("native/include"))
        }
    }
    sourceSets.getByName("commonMain").kotlin.srcDir(generateDesktopDistributionSource)
}

val jvmTestRuntimeClasspath = configurations.named("jvmTestRuntimeClasspath")
val packageJvmRuntimeEvidenceRunner = tasks.register<Zip>("packageJvmRuntimeEvidenceRunner") {
    group = "distribution"
    description = "Packages the portable JVM runtime evidence runner."
    dependsOn("jvmTestClasses")
    archiveFileName.set("codex-agent-jvm-runtime-evidence-runner.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(layout.buildDirectory.dir("classes/kotlin/jvm/main")) { into("classes") }
    from(layout.buildDirectory.dir("classes/kotlin/jvm/test")) { into("classes") }
    from(layout.buildDirectory.dir("processedResources/jvm/main")) { into("classes") }
    from(layout.buildDirectory.dir("processedResources/jvm/test")) { into("classes") }
    from(jvmTestRuntimeClasspath.map { files -> files.filter { it.isDirectory } }) { into("classes") }
    from(jvmTestRuntimeClasspath.map { files -> files.filter { it.isFile } }) { into("lib") }
}
check(desktopManifest.distributions.map { it.target }.toSet() == desktopRuntimeEvidenceTargets.keys) {
    "Desktop evidence target set does not match the distribution manifest"
}
val requestedEvidenceTarget = providers.gradleProperty("codexAgent.desktopEvidenceTarget").orNull
requestedEvidenceTarget?.let { check(it in desktopRuntimeEvidenceTargets) { "Unknown desktop evidence target: $it" } }
desktopManifest.distributions.forEach { distribution ->
    val packageTask = desktopPackageTasks.getValue(distribution)
    val targetTitle = distribution.target.replaceFirstChar(Char::uppercase)
    val validateEvidenceTarget = tasks.register("validate${targetTitle}DesktopEvidenceTarget") {
        inputs.property("requestedTarget", providers.gradleProperty("codexAgent.desktopEvidenceTarget").orElse(""))
        inputs.property("expectedTarget", distribution.target)
        doLast {
            check(inputs.properties.getValue("requestedTarget") == inputs.properties.getValue("expectedTarget")) {
                "-PcodexAgent.desktopEvidenceTarget must equal ${distribution.target}"
            }
        }
    }
    registerJvmRuntimeEvidenceTask(
        distribution,
        packageTask,
        validateEvidenceTarget,
        packageJvmRuntimeEvidenceRunner,
        layout.projectDirectory.file("codex-app-server-distributions.json"),
    )
    val testTaskName = "${distribution.target}Test"
    if (requestedEvidenceTarget == distribution.target) {
        val evidenceRoot = layout.buildDirectory.dir("desktop-runtime-evidence/${distribution.target}")
        tasks.named<KotlinNativeTest>(testTaskName) {
            dependsOn(packageTask)
            environment(
                RUNTIME_BUNDLE_DIRECTORY_ENV,
                packageTask.flatMap { it.outputFile }.get().asFile.parentFile.absolutePath,
            )
            environment(RUNTIME_DATA_DIRECTORY_ENV, evidenceRoot.get().dir("data").asFile.absolutePath)
            environment(RUNTIME_WORKSPACE_ENV, evidenceRoot.get().dir("workspace").asFile.absolutePath)
            doFirst {
                evidenceRoot.get().dir("data").asFile.mkdirs()
                evidenceRoot.get().dir("workspace").asFile.mkdirs()
            }
            outputs.upToDateWhen { false }
        }
    }
    tasks.matching { it.name == testTaskName }.configureEach { mustRunAfter(validateEvidenceTarget) }
    tasks.register<RecordDesktopRuntimeEvidenceTask>("record${targetTitle}DesktopRuntimeEvidence") {
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
        distributionManifest.set(layout.projectDirectory.file("codex-app-server-distributions.json"))
        testReport.set(layout.buildDirectory.file(
            "test-results/${distribution.target}Test/TEST-${distribution.target}Test." +
                "io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeTest.xml",
        ))
        evidenceFile.set(layout.buildDirectory.file(
            "reports/desktop-runtime-evidence/${desktopRuntimeEvidenceFileName(distribution.target)}",
        ))
    }
}

pluginManager.withPlugin("maven-publish") {
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
}
