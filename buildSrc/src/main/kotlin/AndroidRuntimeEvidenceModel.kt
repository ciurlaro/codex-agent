import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val ANDROID_EVIDENCE_FILE = "android-runtime-evidence.json"
internal const val ANDROID_EVIDENCE_APK = "codex-agent-runtime-android-debug-androidTest.apk"
internal const val ANDROID_EVIDENCE_REPORT = "RuntimeBootstrapDeviceTest.xml"
internal const val ANDROID_TEST_APPLICATION_ID = "io.github.ciurlaro.codexmobile.agent.runtime.android.test"
internal const val ANDROID_TESTED_APPLICATION_KIND = "self-contained-instrumentation-apk"
internal const val ANDROID_RUNTIME_TEST_CLASS =
    "io.github.ciurlaro.codexmobile.app.runtime.bootstrap.RuntimeBootstrapDeviceTest"
internal const val APK_RUNTIME_ENTRY = "lib/arm64-v8a/libcodex_app_server.so"
internal const val AAR_RUNTIME_ENTRY = "jni/arm64-v8a/libcodex_app_server.so"

internal val REQUIRED_ANDROID_RUNTIME_TESTS = setOf(
    "missingNonExecutableAndCorruptOverridesFailClosed",
    "successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies",
)

private val ANDROID_EVIDENCE_KEYS = setOf(
    "schemaVersion",
    "commitSha",
    "testCommand",
    "deviceArchitecture",
    "deviceApi",
    "result",
    "testClassName",
    "testReportFileName",
    "testReportSha256",
    "testsRun",
    "instrumentationApkFileName",
    "instrumentationApkSha256",
    "testedApplicationKind",
    "testedApplicationApkSha256",
    "applicationId",
    "instrumentationTargetPackage",
    "builtReleaseAarFileName",
    "releaseAarSha256",
    "apkBundledRuntimeSha256",
    "aarBundledRuntimeSha256",
)

internal data class AndroidRuntimeEvidenceValues(
    val commitSha: String,
    val deviceArchitecture: String,
    val deviceApi: Int,
    val testReportSha256: String,
    val instrumentationApkSha256: String,
    val applicationId: String,
    val instrumentationTargetPackage: String,
    val builtReleaseAarFileName: String,
    val releaseAarSha256: String,
    val apkBundledRuntimeSha256: String,
    val aarBundledRuntimeSha256: String,
)

internal data class AndroidRuntimeEvidenceVerification(
    val evidenceSha256: String,
    val testReportSha256: String,
    val instrumentationApkSha256: String,
    val releaseAarSha256: String,
    val bundledRuntimeSha256: String,
)

internal data class AndroidManifestIdentity(
    val applicationId: String,
    val instrumentationTargetPackage: String,
)

internal fun androidRuntimeEvidenceCommand(commit: String): String =
    "./gradlew :codex-agent-runtime-android:recordAndroidRuntimeEvidence " +
        "-PcodexAgent.candidateCommit=$commit --no-daemon --stacktrace"

internal fun buildAndroidRuntimeEvidence(values: AndroidRuntimeEvidenceValues): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(2))
    put("commitSha", JsonPrimitive(values.commitSha))
    put("testCommand", JsonPrimitive(androidRuntimeEvidenceCommand(values.commitSha)))
    put("deviceArchitecture", JsonPrimitive(values.deviceArchitecture))
    put("deviceApi", JsonPrimitive(values.deviceApi))
    put("result", JsonPrimitive("passed"))
    put("testClassName", JsonPrimitive(ANDROID_RUNTIME_TEST_CLASS))
    put("testReportFileName", JsonPrimitive(ANDROID_EVIDENCE_REPORT))
    put("testReportSha256", JsonPrimitive(values.testReportSha256))
    put("testsRun", JsonPrimitive(REQUIRED_ANDROID_RUNTIME_TESTS.size))
    put("instrumentationApkFileName", JsonPrimitive(ANDROID_EVIDENCE_APK))
    put("instrumentationApkSha256", JsonPrimitive(values.instrumentationApkSha256))
    put("testedApplicationKind", JsonPrimitive(ANDROID_TESTED_APPLICATION_KIND))
    put("testedApplicationApkSha256", JsonPrimitive(values.instrumentationApkSha256))
    put("applicationId", JsonPrimitive(values.applicationId))
    put("instrumentationTargetPackage", JsonPrimitive(values.instrumentationTargetPackage))
    put("builtReleaseAarFileName", JsonPrimitive(values.builtReleaseAarFileName))
    put("releaseAarSha256", JsonPrimitive(values.releaseAarSha256))
    put("apkBundledRuntimeSha256", JsonPrimitive(values.apkBundledRuntimeSha256))
    put("aarBundledRuntimeSha256", JsonPrimitive(values.aarBundledRuntimeSha256))
}

internal fun validateAndroidEvidence(file: File, expectedCommit: String): List<String> =
    validateAndroidEvidence(file.readReleaseObject(), expectedCommit)

internal fun validateAndroidEvidence(evidence: JsonObject, expectedCommit: String): List<String> = buildList {
    if (evidence.keys != ANDROID_EVIDENCE_KEYS) add("schema fields mismatch")
    if (releaseIntOrNull(evidence, "schemaVersion") != 2) add("schema version is not 2")
    if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
    if (evidence.releaseStringOrNull("commitSha") != expectedCommit) add("commit SHA mismatch")
    if (evidence.releaseStringOrNull("testCommand") != androidRuntimeEvidenceCommand(expectedCommit)) {
        add("test command mismatch")
    }
    if (evidence.releaseStringOrNull("deviceArchitecture") != "arm64-v8a") add("device architecture is not ARM64")
    if ((releaseIntOrNull(evidence, "deviceApi") ?: 0) < 26) add("device API is below 26")
    if (evidence.releaseStringOrNull("result") != "passed") add("result is not passed")
    if (evidence.releaseStringOrNull("testClassName") != ANDROID_RUNTIME_TEST_CLASS) add("test class mismatch")
    if (releaseIntOrNull(evidence, "testsRun") != REQUIRED_ANDROID_RUNTIME_TESTS.size) add("test count mismatch")
    if (evidence.releaseStringOrNull("testedApplicationKind") != ANDROID_TESTED_APPLICATION_KIND) {
        add("tested application kind mismatch")
    }
    if (evidence.releaseStringOrNull("applicationId") != ANDROID_TEST_APPLICATION_ID) add("application ID mismatch")
    if (evidence.releaseStringOrNull("instrumentationTargetPackage") != ANDROID_TEST_APPLICATION_ID) {
        add("instrumentation target package mismatch")
    }
    listOf("testReportFileName", "instrumentationApkFileName", "builtReleaseAarFileName").forEach { key ->
        if (!isSafeFileName(evidence.releaseStringOrNull(key))) add("$key is unsafe or missing")
    }
    val hashes = listOf(
        "testReportSha256",
        "instrumentationApkSha256",
        "testedApplicationApkSha256",
        "releaseAarSha256",
        "apkBundledRuntimeSha256",
        "aarBundledRuntimeSha256",
    )
    hashes.forEach { key ->
        if (!isSha256(evidence.releaseStringOrNull(key))) add("$key is invalid")
    }
    if (evidence.releaseStringOrNull("instrumentationApkSha256") !=
        evidence.releaseStringOrNull("testedApplicationApkSha256")
    ) {
        add("tested application does not match the instrumentation APK")
    }
    if (evidence.releaseStringOrNull("apkBundledRuntimeSha256") !=
        evidence.releaseStringOrNull("aarBundledRuntimeSha256")
    ) {
        add("APK and AAR runtime hashes differ")
    }
}

internal fun verifyAndroidRuntimeEvidenceArtifacts(
    evidenceFile: File,
    evidenceDirectory: File,
    stagedAar: File,
    expectedCommit: String,
    pinnedRuntimeSha256: String,
    manifestIdentity: (File) -> AndroidManifestIdentity,
): AndroidRuntimeEvidenceVerification {
    val evidence = evidenceFile.readReleaseObject()
    val errors = validateAndroidEvidence(evidence, expectedCommit)
    check(errors.isEmpty()) { "Android real-runtime evidence is invalid: ${errors.joinToString()}" }
    check(isSha256(pinnedRuntimeSha256)) { "Pinned Android runtime SHA-256 is invalid" }

    val report = safePayloadFile(evidenceDirectory, evidence.releaseString("testReportFileName"))
    check(report.isFile && report.releaseDigest() == evidence.releaseString("testReportSha256")) {
        "Android instrumentation report hash mismatch"
    }
    requirePassingAndroidRuntimeReport(report)

    val apk = safePayloadFile(evidenceDirectory, evidence.releaseString("instrumentationApkFileName"))
    check(apk.isFile && apk.releaseDigest() == evidence.releaseString("instrumentationApkSha256")) {
        "Android instrumentation APK hash mismatch"
    }
    val identity = manifestIdentity(apk)
    check(identity.applicationId == evidence.releaseString("applicationId")) { "Android application ID mismatch" }
    check(identity.instrumentationTargetPackage == evidence.releaseString("instrumentationTargetPackage")) {
        "Android instrumentation target mismatch"
    }
    check(identity.applicationId == identity.instrumentationTargetPackage) {
        "Android test APK is not self-instrumenting"
    }

    check(stagedAar.isFile && stagedAar.releaseDigest() == evidence.releaseString("releaseAarSha256")) {
        "Staged Android AAR hash mismatch"
    }
    val apkRuntime = apk.singleZipEntryDigest(APK_RUNTIME_ENTRY)
    val aarRuntime = stagedAar.singleZipEntryDigest(AAR_RUNTIME_ENTRY)
    check(apkRuntime == evidence.releaseString("apkBundledRuntimeSha256")) { "APK runtime hash mismatch" }
    check(aarRuntime == evidence.releaseString("aarBundledRuntimeSha256")) { "AAR runtime hash mismatch" }
    check(apkRuntime == aarRuntime && apkRuntime == pinnedRuntimeSha256) { "Bundled Android runtime is not pinned" }

    return AndroidRuntimeEvidenceVerification(
        evidenceFile.releaseDigest(),
        report.releaseDigest(),
        apk.releaseDigest(),
        stagedAar.releaseDigest(),
        apkRuntime,
    )
}

private fun releaseIntOrNull(value: JsonObject, name: String): Int? = runCatching { value.releaseInt(name) }.getOrNull()
private fun isSha256(value: String?): Boolean = value.orEmpty().matches(Regex("[0-9a-f]{64}"))
private fun isSafeFileName(value: String?): Boolean = value != null && value == File(value).name && '/' !in value && '\\' !in value
