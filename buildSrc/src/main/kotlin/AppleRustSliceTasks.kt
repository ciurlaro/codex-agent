import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal fun encodedNativeTestCommands(values: List<String>): List<AppleNativeTestCommand> = values.map { value ->
    val parts = value.split('\u001f')
    check(parts.size >= 2 && parts.none(String::isBlank)) { "Malformed Apple native-test command" }
    AppleNativeTestCommand(parts.first(), parts.drop(1))
}

internal fun appleEvidenceIdentity(
    repository: File,
    nativeInputs: Set<File>,
    provenance: File,
    settings: Map<String, String>,
    commit: String,
    tree: String,
    xcode: File,
    swift: File,
): AppleRustEvidenceIdentity = AppleRustEvidenceIdentity(
    commit, tree, appleNativeInputDigest(repository, nativeInputs), provenance.releaseDigest(),
    appleCompilerSettingsDigest(settings), settings.getValue("rustToolchain"), settings.getValue("rustSrcComponent"),
    xcode.releaseDigest(), swift.releaseDigest(),
)

@DisableCachingByDefault(because = "Exports one immutable clean-checkout native artifact")
abstract class ExportAppleRustSliceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val compilerSettings: MapProperty<String, String>
    @get:Input abstract val nativeTestCommands: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputFile abstract val exportedArchive: RegularFileProperty
    @get:OutputFile abstract val sliceProof: RegularFileProperty
    @get:Internal abstract val nativeTestsProof: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun export() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val (commit, tree) = verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val spec = appleRustSliceSpecs.single { it.target == target.get() }
        val source = sourceArchive.get().asFile; verifyStaticArchive(source)
        val destination = exportedArchive.get().asFile
        check(destination.name == spec.archiveName && sliceProof.get().asFile.name == spec.proofName) {
            "Apple Rust export paths are non-canonical"
        }
        destination.parentFile.mkdirs(); Files.copy(source.toPath(), destination.toPath(), REPLACE_EXISTING)
        val identity = appleEvidenceIdentity(
            repository, nativeInputs.files, provenanceFile.get().asFile, compilerSettings.get(), commit, tree,
            xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile,
        )
        sliceProof.get().asFile.atomicWriteJson(buildAppleRustSliceProof(spec, destination, identity))
        val commands = encodedNativeTestCommands(nativeTestCommands.get())
        if (commands.isEmpty()) check(!nativeTestsProof.isPresent) { "Simulator export must not emit native-test proof" }
        else {
            check(spec.target == IOS_DEVICE_RUST_TARGET && nativeTestsProof.isPresent) {
                "Only the device export may emit the native-test proof"
            }
            nativeTestsProof.get().asFile.atomicWriteJson(buildAppleNativeTestsProof(identity, commands))
        }
    }
}

@DisableCachingByDefault(because = "Validates immutable evidence against the current clean checkout")
abstract class ImportAppleRustEvidenceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val compilerSettings: MapProperty<String, String>
    @get:Input abstract val nativeTestCommands: ListProperty<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputDirectory abstract val importedRustDirectory: DirectoryProperty
    @get:Internal abstract val canonicalEvidenceFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun importEvidence() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val (commit, tree) = verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val identity = appleEvidenceIdentity(
            repository, nativeInputs.files, provenanceFile.get().asFile, compilerSettings.get(), commit, tree,
            xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile,
        )
        val evidence = evidenceDirectory.get().asFile
        val commands = encodedNativeTestCommands(nativeTestCommands.get())
        verifyAppleRustEvidenceDirectory(evidence, identity, commands)
        val output = importedRustDirectory.get().asFile
        output.deleteRecursively()
        appleRustSliceSpecs.forEach { spec ->
            val target = output.resolve("${spec.target}/release/$IOS_RUST_LIBRARY")
            target.parentFile.mkdirs(); Files.copy(evidence.resolve(spec.archiveName).toPath(), target.toPath(), REPLACE_EXISTING)
            verifyReleaseRecord(target, evidence.resolve(spec.proofName).readReleaseObject().releaseObject("archive"))
        }
        canonicalEvidenceFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1)); put("protocol", JsonPrimitive("codex-agent-ios-native-evidence-v1"))
            put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(commit))
            put("candidateTree", JsonPrimitive(tree)); put("cleanCheckout", JsonPrimitive(true))
            put("nativeInputsSha256", JsonPrimitive(identity.nativeInputsSha256))
            put("nativeProvenanceSha256", JsonPrimitive(identity.provenanceSha256))
            put("compilerSettingsSha256", JsonPrimitive(identity.compilerSettingsSha256))
            put("rustToolchain", JsonPrimitive(identity.rustToolchain))
            put("rustSrcComponent", JsonPrimitive(identity.rustSrcComponent))
            put("xcodeVersionSha256", JsonPrimitive(identity.xcodeVersionSha256))
            put("swiftVersionSha256", JsonPrimitive(identity.swiftVersionSha256))
            put("nativeTestsProofSha256", JsonPrimitive(evidence.resolve(IOS_NATIVE_TESTS_PROOF).releaseDigest()))
            put("slices", buildJsonArray { appleRustSliceSpecs.forEach { spec -> add(buildJsonObject {
                put("target", JsonPrimitive(spec.target)); put("archive", evidence.resolve(spec.archiveName).releaseRecord())
                put("proofSha256", JsonPrimitive(evidence.resolve(spec.proofName).releaseDigest()))
            }) } })
        })
    }
}

private fun verifyAppleEvidenceCheckout(
    exec: ExecOperations,
    repository: File,
    expectedCommit: String,
): Pair<String, String> {
    check(expectedCommit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit must be 40 lowercase hexadecimal characters" }
    fun git(vararg args: String): String {
        val output = ByteArrayOutputStream()
        exec.exec { workingDir(repository); commandLine("git", *args); standardOutput = output }.assertNormalExitValue()
        return output.toString(UTF_8).trim()
    }
    val commit = git("rev-parse", "HEAD^{commit}")
    check(commit == expectedCommit) { "Apple native evidence checkout commit mismatch" }
    val status = git("status", "--porcelain=v1", "--untracked-files=normal")
    check(status.isBlank()) { "Apple native evidence requires a clean checkout:\n$status" }
    return commit to git("rev-parse", "$commit^{tree}")
}
