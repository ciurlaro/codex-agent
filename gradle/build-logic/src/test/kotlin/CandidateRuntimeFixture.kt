import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val FIXTURE_ANDROID_RUNTIME_BYTES = "Android ARM64 runtime".encodeToByteArray()

internal class CandidateRuntimeReleaseFixture(
    root: File,
    private val version: String,
    private val commit: String,
) {
    private val appServer = "official app server".encodeToByteArray()
    private val supervisor = "process supervisor".encodeToByteArray()
    private val appServerSha = appServer.inputStream().releaseDigest()
    private val supervisorSha = supervisor.inputStream().releaseDigest()
    val distributionManifest = writeTestDesktopDistributionManifest(
        root.resolve("codex-app-server-distributions.json"), appServerSha,
    )
    val classifiers = desktopRuntimeEvidenceTargets.mapValues { (target, spec) ->
        root.resolve("codex-agent-runtime-desktop-$version-${spec.classifier}.zip").apply {
            parentFile.mkdirs()
            val windows = target == "mingwX64"
            val executable = if (windows) "codex-app-server.exe" else "codex-app-server"
            val supervisorExecutable = if (windows) "codex-process-supervisor.exe" else "codex-process-supervisor"
            val payload = linkedMapOf(
                executable to appServer,
                supervisorExecutable to supervisor,
                "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
                "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
            )
            nodeEvidenceWriteZip(payload + ("codex-runtime-manifest.json" to runtimeManifestFixture(
                version, target, spec.classifier, payload, setOf(executable, supervisorExecutable),
            )))
        }
    }
    private val classifierProofs = desktopRuntimeEvidenceTargets.keys.associateWith { target ->
        inspectDesktopClassifier(target, readDesktopCodexManifest(distributionManifest), classifiers.getValue(target))
    }
    val desktopEvidence = desktopRuntimeEvidenceTargets.keys.map { target ->
        root.resolve(desktopRuntimeEvidenceFileName(target)).apply {
            atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
                commit, target, appServerSha, supervisorSha, classifiers.getValue(target).releaseDigest(),
            )))
        }
    }
    val jvmRunner = root.resolve(JVM_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(linkedMapOf(
            "classes/${JVM_RUNTIME_RUNNER_ENTRYPOINT.replace('.', '/')}.class" to "class".encodeToByteArray(),
            "lib/runtime.jar" to "jar".encodeToByteArray(),
        ))
    }
    val jvmEvidence = desktopRuntimeEvidenceTargets.keys.map { target ->
        root.resolve(jvmRuntimeEvidenceFileName(target)).apply {
            atomicWriteJson(buildJvmRuntimeEvidence(JvmRuntimeEvidenceValues(
                commit, target, classifierProofs.getValue(target), jvmRunner,
            )))
        }
    }
    val nodeRunner = root.resolve(NODE_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(mapOf(NODE_RUNTIME_RUNNER_ENTRY to "compiled JS runner".encodeToByteArray()))
    }
    val nodeWasmRunner = root.resolve(NODE_WASM_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(nodeWasmRuntimeRunnerEntries.associateWith { it.encodeToByteArray() })
    }
    val nodeEvidence = writeNodeEvidence(root, NODE_RUNTIME_JS_BACKEND, nodeRunner)
    val nodeWasmEvidence = writeNodeEvidence(root, NODE_RUNTIME_WASM_BACKEND, nodeWasmRunner)

    private val matrix = root.resolve(FIREBASE_MATRIX_FILE).apply { writeText("firebase matrix") }
    private val report = root.resolve(FIREBASE_ANDROID_REPORT).apply { writeText("passing report") }
    private val applicationApk = root.resolve(FIREBASE_APPLICATION_APK).apply { writeText("application APK") }
    private val testApk = root.resolve(FIREBASE_TEST_APK).apply { writeText("test APK") }
    private val androidRuntime = FIXTURE_ANDROID_RUNTIME_BYTES
    private val androidRuntimeSha = androidRuntime.inputStream().releaseDigest()
    private val releaseAar = root.resolve(FIREBASE_RELEASE_AAR).apply {
        writeBytes(zipBytes(mapOf(AAR_RUNTIME_ENTRY to androidRuntime)))
    }
    private val androidRecord = root.resolve(FIREBASE_ANDROID_EVIDENCE_FILE).apply {
        atomicWriteJson(buildFirebaseAndroidEvidence(FirebaseAndroidEvidenceValues(
            commit,
            FirebaseTestMatrix(
                "matrix-fixture", "fixture-project", "gs://fixture/results", FIREBASE_DEVICE_MODEL,
                FIREBASE_DEVICE_API, FIREBASE_DEVICE_LOCALE, FIREBASE_DEVICE_ORIENTATION,
            ),
            matrix.releaseDigest(), report.releaseDigest(), applicationApk.releaseDigest(),
            testApk.releaseDigest(), releaseAar.releaseDigest(), androidRuntimeSha, androidRuntimeSha,
        )))
    }
    private val androidReceipt = root.resolve(FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE).apply {
        atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1)); put("result", JsonPrimitive("passed"))
            put("evidenceSha256", JsonPrimitive(androidRecord.releaseDigest()))
            put("firebaseMatrixSha256", JsonPrimitive(matrix.releaseDigest()))
            put("testReportSha256", JsonPrimitive(report.releaseDigest()))
            put("applicationApkSha256", JsonPrimitive(applicationApk.releaseDigest()))
            put("testApkSha256", JsonPrimitive(testApk.releaseDigest()))
            put("releaseAarSha256", JsonPrimitive(releaseAar.releaseDigest()))
            put("bundledRuntimeSha256", JsonPrimitive(androidRuntimeSha))
        })
    }
    val androidEvidence = listOf(
        androidRecord, matrix, report, applicationApk, testApk, releaseAar, androidReceipt,
    )

    fun writeCentralBundle(output: File, runtime: ByteArray = androidRuntime): File = output.apply {
        parentFile.mkdirs()
        nodeEvidenceWriteZip(mapOf(
            "io/github/ciurlaro/codex-agent-runtime-android/$version/" +
                "codex-agent-runtime-android-$version.aar" to zipBytes(mapOf(AAR_RUNTIME_ENTRY to runtime)),
        ))
    }

    private fun writeNodeEvidence(root: File, backend: String, runner: File): List<File> =
        desktopRuntimeEvidenceTargets.keys.map { target ->
            root.resolve(nodeRuntimeEvidenceFileName(target, backend)).apply {
                atomicWriteJson(buildNodeRuntimeEvidence(NodeRuntimeEvidenceValues(
                    commit, target, backend, classifierProofs.getValue(target), runner,
                )))
            }
        }

    fun mavenRecords(): List<JsonObject> = desktopRuntimeEvidenceTargets.map { (target, spec) ->
        val archive = classifiers.getValue(target)
        buildJsonObject {
            put("path", JsonPrimitive(
                "io/github/ciurlaro/codex-agent-runtime-desktop/$version/" +
                    "codex-agent-runtime-desktop-$version-${spec.classifier}.zip",
            ))
            put("bytes", JsonPrimitive(archive.length()))
            put("sha256", JsonPrimitive(archive.releaseDigest()))
        }
    }
}

internal fun runtimeManifestFixture(
    libraryVersion: String,
    target: String,
    classifier: String,
    payload: Map<String, ByteArray>,
    executables: Set<String>,
): ByteArray = buildJsonObject {
    put("schemaVersion", 1)
    put("libraryVersion", libraryVersion)
    put("appServerVersion", "0.145.0")
    put("target", target)
    put("classifier", classifier)
    putJsonArray("members") {
        payload.forEach { (name, bytes) ->
            add(buildJsonObject {
                put("name", name)
                put("size", bytes.size)
                put("sha256", bytes.inputStream().releaseDigest())
                put("executable", name in executables)
            })
        }
    }
}.toString().encodeToByteArray()

internal fun assertRuntimeBundleEnvironment(environment: Map<String, String>, target: String) {
    assertEquals(
        setOf(
            RUNTIME_BUNDLE_DIRECTORY_ENV,
            RUNTIME_DATA_DIRECTORY_ENV,
            RUNTIME_WORKSPACE_ENV,
            "CODEX_AGENT_DESKTOP_TARGET",
        ),
        environment.keys,
    )
    assertEquals(target, environment["CODEX_AGENT_DESKTOP_TARGET"])
    val bundle = File(environment.getValue(RUNTIME_BUNDLE_DIRECTORY_ENV))
    assertTrue(bundle.isDirectory)
    assertTrue(File(environment.getValue(RUNTIME_DATA_DIRECTORY_ENV)).isDirectory)
    assertTrue(File(environment.getValue(RUNTIME_WORKSPACE_ENV)).isDirectory)
    assertTrue(
        bundle.resolve(
            "codex-agent-runtime-desktop-0.2.0-${desktopRuntimeEvidenceTargets.getValue(target).classifier}.zip",
        ).isFile,
    )
}

internal fun zipBytes(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)) })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    output.toByteArray()
}
