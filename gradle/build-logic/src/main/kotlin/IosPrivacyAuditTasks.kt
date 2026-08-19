import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Candidate privacy evidence must inspect the exact static archives")
abstract class GenerateIosPrivacyEvidenceTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xcframeworkDirectory: DirectoryProperty

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archivedApplicationDirectory: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyManifest: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val dataFlowReview: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val llvmNmExecutable: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val stringsExecutable: RegularFileProperty

    @get:OutputFile abstract val policyFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val llvmNm = llvmNmExecutable.get().asFile
        val strings = stringsExecutable.get().asFile
        check(llvmNm.canExecute()) { "Pinned Rust llvm-nm is unavailable: $llvmNm" }
        check(strings.canExecute()) { "Xcode strings is unavailable: $strings" }
        generateIosPrivacyEvidence(
            xcframeworkDirectory.get().asFile,
            archivedApplicationDirectory.get().asFile,
            privacyManifest.get().asFile,
            dataFlowReview.get().asFile,
            policyFile.get().asFile,
            evidenceFile.get().asFile,
            buildJsonArray {
                add(toolRecord("llvm-nm", llvmNm))
                add(toolRecord("strings", strings))
            },
        ) { objectFile -> inspectObject(llvmNm, strings, objectFile) }
    }

    private fun inspectObject(llvmNm: File, strings: File, objectFile: File): IosPrivacySignals {
        val symbolsOutput = ByteArrayOutputStream()
        runTool(llvmNm, listOf("--undefined-only", "--format=just-symbols", objectFile.path), symbolsOutput)
        val symbols = symbolsOutput.toString(Charsets.UTF_8).lineSequence()
            .map(String::trim).filter(String::isNotEmpty).map { it.substringBefore('$') }.toSet()
        val tokenOutput = PrivacyTokenOutputStream(IosPrivacyPolicy.stringTokens)
        runTool(strings, listOf("-a", objectFile.path), tokenOutput)
        return IosPrivacySignals(symbols, tokenOutput.matches)
    }

    private fun runTool(executable: File, arguments: List<String>, output: OutputStream) {
        val errors = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(executable.path, *arguments.toTypedArray())
            standardOutput = output
            errorOutput = errors
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) {
            "Privacy inspection tool failed (${executable.name}): ${errors.toString(Charsets.UTF_8).trim()}"
        }
    }

    private fun toolRecord(name: String, executable: File) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("executable", executable.releaseRecord(executable.name))
    }
}

@CacheableTask
abstract class VerifyIosPrivacyAuditTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val policyFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val reviewFile: RegularFileProperty
    @get:OutputFile abstract val auditFile: RegularFileProperty

    @TaskAction
    fun verify() = verifyIosPrivacyAudit(
        policyFile.get().asFile,
        evidenceFile.get().asFile,
        reviewFile.orNull?.asFile,
        auditFile.get().asFile,
    )
}

private class PrivacyTokenOutputStream(tokens: Set<String>) : OutputStream() {
    private val encoded = tokens.associateWith(String::encodeToByteArray)
    private val tailSize = (encoded.values.maxOfOrNull(ByteArray::size) ?: 1) - 1
    private var tail = ByteArray(0)
    val matches = sortedSetOf<String>()

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val combined = tail + bytes.copyOfRange(offset, offset + length)
        encoded.forEach { (token, pattern) -> if (combined.indexOf(pattern) >= 0) matches += token }
        tail = combined.takeLast(tailSize).toByteArray()
    }
}

private fun ByteArray.indexOf(pattern: ByteArray): Int {
    if (pattern.isEmpty()) return 0
    for (start in 0..size - pattern.size) {
        if (pattern.indices.all { this[start + it] == pattern[it] }) return start
    }
    return -1
}
