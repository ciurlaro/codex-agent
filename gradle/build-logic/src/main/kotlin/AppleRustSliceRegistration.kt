import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal data class AppleRustSliceSelection(
    val deviceArchive: Provider<RegularFile>,
    val simulatorArchive: Provider<RegularFile>,
    val prepareDevice: TaskProvider<Task>,
    val prepareSimulator: TaskProvider<Task>,
)

internal data class AppleRustSliceRegistrationInputs(
    val candidateCommit: Provider<String>,
    val evidenceDirectory: Provider<Directory>?,
    val nativeInputs: ConfigurableFileCollection,
    val provenanceFile: Provider<RegularFile>,
    val compilerSettings: Map<String, String>,
    val rustCompilerIdentity: Provider<String>,
    val appleToolchainIdentities: Map<String, Provider<String>>,
    val deviceBuild: TaskProvider<out Task>,
    val simulatorBuild: TaskProvider<out Task>,
    val bridgeTest: TaskProvider<out Task>,
    val directToolTest: TaskProvider<out Task>,
    val deviceArchive: Provider<RegularFile>,
    val simulatorArchive: Provider<RegularFile>,
)

internal fun Project.registerAppleRustSliceReuse(inputs: AppleRustSliceRegistrationInputs): AppleRustSliceSelection {
    val repository = rootProject.layout.projectDirectory
    val provenance = inputs.provenanceFile
    val xcode = layout.buildDirectory.file("reports/ios-release/toolchain/xcode.txt")
    val swift = layout.buildDirectory.file("reports/ios-release/toolchain/swift.txt")
    val exportDirectory = layout.buildDirectory.dir("apple-slice-exports")
    val commands = listOf(
        encodeNativeCommand(taskPath("testCodexIosBridge"), listOf("test", "--locked", "-p", "codex-agent-ios-bridge", "--lib")),
        encodeNativeCommand(
            taskPath("testCodexIosDirectToolMode"),
            listOf("test", "--locked", "-p", "codex-core", "--lib", "ios_runtime_forces_direct_tools_for_code_mode_only_models"),
        ),
    )
    fun registerExport(
        name: String,
        spec: AppleRustSliceSpec,
        source: Provider<RegularFile>,
        dependencies: List<TaskProvider<out Task>>,
    ) = tasks.register<ExportAppleRustSliceTask>(name) {
        dependsOn(dependencies); dependsOn("verifyAppleToolchain")
        candidateCommit.set(inputs.candidateCommit); target.set(spec.target); compilerSettings.putAll(inputs.compilerSettings)
        rustCompilerIdentity.set(inputs.rustCompilerIdentity)
        appleToolchainIdentity.set(inputs.appleToolchainIdentities.getValue(spec.target))
        sourceArchive.set(source); provenanceFile.set(provenance); xcodeVersionFile.set(xcode); swiftVersionFile.set(swift)
        nativeInputs.from(inputs.nativeInputs); repositoryDirectory.set(repository)
        exportedArchive.set(exportDirectory.map { it.file(spec.archiveName) })
        sliceProof.set(exportDirectory.map { it.file(spec.proofName) })
    }
    registerExport(
        "exportCodexAgentIosArm64RustSlice", appleRustSliceSpecs[0], inputs.deviceArchive,
        listOf(inputs.deviceBuild),
    )
    registerExport(
        "exportCodexAgentIosSimulatorArm64RustSlice", appleRustSliceSpecs[1], inputs.simulatorArchive,
        listOf(inputs.simulatorBuild),
    )
    tasks.register<ExportAppleNativeTestsProofTask>("exportCodexAgentIosNativeTestsProof") {
        dependsOn(inputs.bridgeTest, inputs.directToolTest)
        candidateCommit.set(inputs.candidateCommit)
        rustToolchain.set(inputs.compilerSettings.getValue("rustToolchain"))
        rustSrcComponent.set("not-required")
        nativeTestCommands.set(commands)
        provenanceFile.set(provenance)
        nativeInputs.from(inputs.nativeInputs)
        repositoryDirectory.set(repository)
        nativeTestsProof.set(exportDirectory.map { it.file("native-tests/$IOS_NATIVE_TESTS_PROOF") })
    }

    val prepareDevice = tasks.register("prepareCodexAgentIosArm64RustSlice")
    val prepareSimulator = tasks.register("prepareCodexAgentIosSimulatorArm64RustSlice")
    val sliceEvidence = mapOf(
        IOS_DEVICE_RUST_TARGET to providers.gradleProperty("codexAgent.iosDeviceRustEvidenceDirectory"),
        IOS_SIMULATOR_RUST_TARGET to providers.gradleProperty("codexAgent.iosSimulatorRustEvidenceDirectory"),
    ).mapValues { (_, path) -> path.map { rootProject.file(it) }.let(layout::dir).takeIf { it.isPresent } }
    if (inputs.evidenceDirectory == null && sliceEvidence.values.any { it != null }) {
        fun importSlice(spec: AppleRustSliceSpec): Pair<Provider<RegularFile>, TaskProvider<out Task>> {
            val evidence = sliceEvidence.getValue(spec.target)
            if (evidence == null) {
                return if (spec.target == IOS_DEVICE_RUST_TARGET) inputs.deviceArchive to inputs.deviceBuild
                else inputs.simulatorArchive to inputs.simulatorBuild
            }
            val output = layout.buildDirectory.file("imported-rust-slices/${spec.target}/release/$IOS_RUST_LIBRARY")
            val title = if (spec.target == IOS_DEVICE_RUST_TARGET) "IosArm64" else "IosSimulatorArm64"
            val task = tasks.register<ImportAppleRustSliceTask>("importCodexAgent${title}RustSlice") {
                dependsOn("verifyAppleToolchain")
                candidateCommit.set(inputs.candidateCommit); target.set(spec.target)
                compilerSettings.putAll(inputs.compilerSettings); rustCompilerIdentity.set(inputs.rustCompilerIdentity)
                appleToolchainIdentity.set(inputs.appleToolchainIdentities.getValue(spec.target))
                evidenceDirectory.set(evidence); provenanceFile.set(provenance)
                xcodeVersionFile.set(xcode); swiftVersionFile.set(swift); nativeInputs.from(inputs.nativeInputs)
                repositoryDirectory.set(repository); importedArchive.set(output)
            }
            return output to task
        }
        val device = importSlice(appleRustSliceSpecs.single { it.target == IOS_DEVICE_RUST_TARGET })
        val simulator = importSlice(appleRustSliceSpecs.single { it.target == IOS_SIMULATOR_RUST_TARGET })
        prepareDevice.configure { dependsOn(device.second) }
        prepareSimulator.configure { dependsOn(simulator.second) }
        return AppleRustSliceSelection(device.first, simulator.first, prepareDevice, prepareSimulator)
    }
    if (inputs.evidenceDirectory == null) {
        prepareDevice.configure { dependsOn(inputs.deviceBuild) }
        prepareSimulator.configure { dependsOn(inputs.simulatorBuild) }
        return AppleRustSliceSelection(inputs.deviceArchive, inputs.simulatorArchive, prepareDevice, prepareSimulator)
    }

    val imported = layout.buildDirectory.dir("imported-rust")
    val validate = tasks.register<ImportAppleRustEvidenceTask>("validateImportedCodexAgentIosNativeEvidence") {
        dependsOn("verifyAppleToolchain")
        candidateCommit.set(inputs.candidateCommit); compilerSettings.putAll(inputs.compilerSettings)
        rustCompilerIdentity.set(inputs.rustCompilerIdentity)
        inputs.appleToolchainIdentities.forEach { (target, identity) -> appleToolchainIdentities.put(target, identity) }
        nativeTestRustToolchain.set(inputs.compilerSettings.getValue("rustToolchain"))
        nativeTestRustSrcComponent.set("not-required")
        nativeTestCommands.set(commands); evidenceDirectory.set(inputs.evidenceDirectory)
        provenanceFile.set(provenance); xcodeVersionFile.set(xcode); swiftVersionFile.set(swift)
        nativeInputs.from(inputs.nativeInputs); repositoryDirectory.set(repository)
        importedRustDirectory.set(imported); canonicalEvidenceFile.set(imported.map { it.file("ios-native-evidence.json") })
    }
    val importDevice = tasks.register("importCodexAgentIosArm64RustSlice") { dependsOn(validate) }
    val importSimulator = tasks.register("importCodexAgentIosSimulatorArm64RustSlice") { dependsOn(validate) }
    prepareDevice.configure { dependsOn(importDevice) }; prepareSimulator.configure { dependsOn(importSimulator) }
    return AppleRustSliceSelection(
        imported.map { it.file("$IOS_DEVICE_RUST_TARGET/release/$IOS_RUST_LIBRARY") },
        imported.map { it.file("$IOS_SIMULATOR_RUST_TARGET/release/$IOS_RUST_LIBRARY") },
        prepareDevice, prepareSimulator,
    )
}

private fun Project.taskPath(name: String) = if (path == ":") ":$name" else "$path:$name"
private fun encodeNativeCommand(taskPath: String, cargoArguments: List<String>) =
    (listOf(taskPath) + cargoArguments).joinToString("\u001f")
