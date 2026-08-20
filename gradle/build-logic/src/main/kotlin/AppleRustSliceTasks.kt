import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
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
    rustCompilerIdentity: String,
    appleToolchainIdentity: String,
    xcode: File,
    swift: File,
): AppleRustEvidenceIdentity = AppleRustEvidenceIdentity(
    commit, tree, appleNativeInputDigest(repository, nativeInputs), provenance.releaseDigest(),
    appleCompilerSettingsDigest(settings), settings.getValue("rustToolchain"), settings.getValue("rustSrcComponent"),
    rustCompilerIdentity.byteInputStream().releaseDigest(), appleToolchainIdentity.byteInputStream().releaseDigest(),
    xcode.releaseDigest(), swift.releaseDigest(),
)

internal fun appleNativeTestsIdentity(
    repository: File,
    nativeInputs: Set<File>,
    provenance: File,
    commit: String,
    tree: String,
    rustToolchain: String,
    rustSrcComponent: String,
): AppleNativeTestsIdentity = AppleNativeTestsIdentity(
    commit, tree, appleNativeInputDigest(repository, nativeInputs), provenance.releaseDigest(),
    rustToolchain, rustSrcComponent,
)

@DisableCachingByDefault(because = "Exports one immutable clean-checkout native artifact")
abstract class ExportAppleRustSliceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val compilerSettings: MapProperty<String, String>
    @get:Input abstract val rustCompilerIdentity: Property<String>
    @get:Input abstract val appleToolchainIdentity: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputFile abstract val exportedArchive: RegularFileProperty
    @get:OutputFile abstract val sliceProof: RegularFileProperty

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
            rustCompilerIdentity.get(), appleToolchainIdentity.get(),
            xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile,
        )
        sliceProof.get().asFile.atomicWriteJson(buildAppleRustSliceProof(spec, destination, identity))
    }
}

@DisableCachingByDefault(because = "Validates one immutable Rust slice against the current checkout")
abstract class ImportAppleRustSliceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val compilerSettings: MapProperty<String, String>
    @get:Input abstract val rustCompilerIdentity: Property<String>
    @get:Input abstract val appleToolchainIdentity: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputFile abstract val importedArchive: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun importSlice() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val spec = appleRustSliceSpecs.single { it.target == target.get() }
        val evidence = evidenceDirectory.get().asFile
        val actual = evidence.listFiles()?.onEach {
            check(it.isFile && !Files.isSymbolicLink(it.toPath())) { "Apple Rust slice contains an unsafe entry" }
        }?.map(File::getName)?.toSet().orEmpty()
        check(actual == setOf(spec.archiveName, spec.proofName)) { "Apple Rust slice file set mismatch" }
        val archive = evidence.resolve(spec.archiveName)
        verifyStaticArchive(archive)
        val proof = evidence.resolve(spec.proofName).readReleaseObject()
        val (producerCommit, producerTree) = appleProofProducerIdentity(proof)
        val identity = appleEvidenceIdentity(
            repository, nativeInputs.files, provenanceFile.get().asFile, compilerSettings.get(), producerCommit, producerTree,
            rustCompilerIdentity.get(), appleToolchainIdentity.get(),
            xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile,
        )
        verifySliceProof(proof, spec, archive, identity)
        val output = importedArchive.get().asFile
        output.parentFile.mkdirs()
        Files.copy(archive.toPath(), output.toPath(), REPLACE_EXISTING)
    }
}

@DisableCachingByDefault(because = "Exports fresh host-test evidence from a clean checkout")
abstract class ExportAppleNativeTestsProofTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val rustToolchain: Property<String>
    @get:Input abstract val rustSrcComponent: Property<String>
    @get:Input abstract val nativeTestCommands: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputFile abstract val nativeTestsProof: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun export() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val (commit, tree) = verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val identity = appleNativeTestsIdentity(
            repository, nativeInputs.files, provenanceFile.get().asFile, commit, tree,
            rustToolchain.get(), rustSrcComponent.get(),
        )
        nativeTestsProof.get().asFile.atomicWriteJson(
            buildAppleNativeTestsProof(identity, encodedNativeTestCommands(nativeTestCommands.get())),
        )
    }
}

@DisableCachingByDefault(because = "Validates immutable evidence against the current clean checkout")
abstract class ImportAppleRustEvidenceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val compilerSettings: MapProperty<String, String>
    @get:Input abstract val rustCompilerIdentity: Property<String>
    @get:Input abstract val appleToolchainIdentities: MapProperty<String, String>
    @get:Input abstract val nativeTestRustToolchain: Property<String>
    @get:Input abstract val nativeTestRustSrcComponent: Property<String>
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
        val evidence = evidenceDirectory.get().asFile
        val identities = appleRustSliceSpecs.associate { spec ->
            val (producerCommit, producerTree) = appleProofProducerIdentity(
                evidence.resolve(spec.proofName).readReleaseObject(),
            )
            spec.target to appleEvidenceIdentity(
                repository, nativeInputs.files, provenanceFile.get().asFile, compilerSettings.get(),
                producerCommit, producerTree,
                rustCompilerIdentity.get(), appleToolchainIdentities.get().getValue(spec.target),
                xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile,
            )
        }
        val identity = identities.values.first()
        val (nativeTestsCommit, nativeTestsTree) = appleProofProducerIdentity(
            evidence.resolve(IOS_NATIVE_TESTS_PROOF).readReleaseObject(),
        )
        val nativeTestsIdentity = appleNativeTestsIdentity(
            repository, nativeInputs.files, provenanceFile.get().asFile, nativeTestsCommit, nativeTestsTree,
            nativeTestRustToolchain.get(), nativeTestRustSrcComponent.get(),
        )
        val commands = encodedNativeTestCommands(nativeTestCommands.get())
        verifyAppleRustEvidenceDirectory(evidence, identities, nativeTestsIdentity, commands)
        val output = importedRustDirectory.get().asFile
        output.deleteRecursively()
        appleRustSliceSpecs.forEach { spec ->
            val target = output.resolve("${spec.target}/release/$IOS_RUST_LIBRARY")
            target.parentFile.mkdirs(); Files.copy(evidence.resolve(spec.archiveName).toPath(), target.toPath(), REPLACE_EXISTING)
            verifyReleaseRecord(target, evidence.resolve(spec.proofName).readReleaseObject().releaseObject("archive"))
        }
        canonicalEvidenceFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(2)); put("protocol", JsonPrimitive("codex-agent-ios-native-evidence-v2"))
            put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(commit))
            put("candidateTree", JsonPrimitive(tree)); put("cleanCheckout", JsonPrimitive(true))
            put("nativeInputsSha256", JsonPrimitive(identity.nativeInputsSha256))
            put("nativeProvenanceSha256", JsonPrimitive(identity.provenanceSha256))
            put("compilerSettingsSha256", JsonPrimitive(identity.compilerSettingsSha256))
            put("rustToolchain", JsonPrimitive(identity.rustToolchain))
            put("rustSrcComponent", JsonPrimitive(identity.rustSrcComponent))
            put("rustCompilerIdentitySha256", JsonPrimitive(identity.rustCompilerIdentitySha256))
            put("xcodeVersionSha256", JsonPrimitive(identity.xcodeVersionSha256))
            put("swiftVersionSha256", JsonPrimitive(identity.swiftVersionSha256))
            put("nativeTestsProofSha256", JsonPrimitive(evidence.resolve(IOS_NATIVE_TESTS_PROOF).releaseDigest()))
            put("slices", buildJsonArray { appleRustSliceSpecs.forEach { spec -> add(buildJsonObject {
                put("target", JsonPrimitive(spec.target)); put("archive", evidence.resolve(spec.archiveName).releaseRecord())
                put("proofSha256", JsonPrimitive(evidence.resolve(spec.proofName).releaseDigest()))
                put("appleToolchainIdentitySha256", JsonPrimitive(
                    identities.getValue(spec.target).appleToolchainIdentitySha256,
                ))
            }) } })
        })
    }
}

internal fun appleProofProducerIdentity(proof: JsonObject): Pair<String, String> {
    val commit = proof.releaseString("candidateCommit")
    val tree = proof.releaseString("candidateTree")
    check(listOf(commit, tree).all { value ->
        value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }) { "Apple proof producer Git identity is invalid" }
    return commit to tree
}

internal fun verifyAppleEvidenceCheckout(
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
