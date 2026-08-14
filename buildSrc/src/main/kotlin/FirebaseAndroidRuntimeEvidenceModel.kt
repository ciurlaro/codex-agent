import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val FIREBASE_ANDROID_EVIDENCE_FILE = "android-runtime-evidence.json"
internal const val FIREBASE_MATRIX_FILE = "firebase-test-matrix.json"
internal const val FIREBASE_ANDROID_REPORT = "RuntimeBootstrapDeviceTest.xml"
internal const val FIREBASE_APPLICATION_APK = "android-runtime-evidence-debug.apk"
internal const val FIREBASE_TEST_APK = "android-runtime-evidence-debug-androidTest.apk"
internal const val FIREBASE_RELEASE_AAR = "codex-agent-runtime-android-release.aar"
internal const val FIREBASE_DEVICE_MODEL = "SmallPhone.arm"
internal const val FIREBASE_DEVICE_API = 35
internal const val FIREBASE_DEVICE_ARCHITECTURE = "arm64-v8a"
internal const val FIREBASE_DEVICE_LOCALE = "en"
internal const val FIREBASE_DEVICE_ORIENTATION = "portrait"
internal const val FIREBASE_APPLICATION_ID = "io.github.ciurlaro.codexagent.androidruntimeevidence"
internal const val FIREBASE_TEST_APPLICATION_ID = "$FIREBASE_APPLICATION_ID.test"
internal const val FIREBASE_TESTED_APPLICATION_KIND = "host-app-plus-test-apk"

private val FIREBASE_ANDROID_EVIDENCE_KEYS = setOf(
    "schemaVersion", "commitSha", "result", "firebaseMatrixId", "firebaseProjectId",
    "firebaseResultsUri", "firebaseMatrixFileName", "firebaseMatrixSha256", "deviceModel",
    "deviceApi", "deviceArchitecture", "deviceLocale", "deviceOrientation", "testClassName",
    "testReportFileName", "testReportSha256", "testsRun", "testedApplicationKind",
    "applicationApkFileName", "applicationApkSha256", "testApkFileName", "testApkSha256",
    "applicationId", "testApplicationId", "instrumentationTargetPackage",
    "builtReleaseAarFileName", "releaseAarSha256", "appBundledRuntimeSha256",
    "aarBundledRuntimeSha256",
)

internal data class FirebaseTestMatrix(
    val id: String,
    val projectId: String,
    val resultsUri: String,
    val model: String,
    val api: Int,
    val locale: String,
    val orientation: String,
)

internal data class FirebaseAndroidEvidenceValues(
    val commitSha: String,
    val matrix: FirebaseTestMatrix,
    val matrixSha256: String,
    val testReportSha256: String,
    val applicationApkSha256: String,
    val testApkSha256: String,
    val releaseAarSha256: String,
    val appBundledRuntimeSha256: String,
    val aarBundledRuntimeSha256: String,
)

internal data class FirebaseAndroidEvidenceVerification(
    val evidenceSha256: String,
    val matrixSha256: String,
    val testReportSha256: String,
    val applicationApkSha256: String,
    val testApkSha256: String,
    val releaseAarSha256: String,
    val bundledRuntimeSha256: String,
)

internal fun buildFirebaseAndroidEvidence(values: FirebaseAndroidEvidenceValues): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(3))
    put("commitSha", JsonPrimitive(values.commitSha))
    put("result", JsonPrimitive("passed"))
    put("firebaseMatrixId", JsonPrimitive(values.matrix.id))
    put("firebaseProjectId", JsonPrimitive(values.matrix.projectId))
    put("firebaseResultsUri", JsonPrimitive(values.matrix.resultsUri))
    put("firebaseMatrixFileName", JsonPrimitive(FIREBASE_MATRIX_FILE))
    put("firebaseMatrixSha256", JsonPrimitive(values.matrixSha256))
    put("deviceModel", JsonPrimitive(values.matrix.model))
    put("deviceApi", JsonPrimitive(values.matrix.api))
    put("deviceArchitecture", JsonPrimitive(FIREBASE_DEVICE_ARCHITECTURE))
    put("deviceLocale", JsonPrimitive(values.matrix.locale))
    put("deviceOrientation", JsonPrimitive(values.matrix.orientation))
    put("testClassName", JsonPrimitive(ANDROID_RUNTIME_TEST_CLASS))
    put("testReportFileName", JsonPrimitive(FIREBASE_ANDROID_REPORT))
    put("testReportSha256", JsonPrimitive(values.testReportSha256))
    put("testsRun", JsonPrimitive(REQUIRED_ANDROID_RUNTIME_TESTS.size))
    put("testedApplicationKind", JsonPrimitive(FIREBASE_TESTED_APPLICATION_KIND))
    put("applicationApkFileName", JsonPrimitive(FIREBASE_APPLICATION_APK))
    put("applicationApkSha256", JsonPrimitive(values.applicationApkSha256))
    put("testApkFileName", JsonPrimitive(FIREBASE_TEST_APK))
    put("testApkSha256", JsonPrimitive(values.testApkSha256))
    put("applicationId", JsonPrimitive(FIREBASE_APPLICATION_ID))
    put("testApplicationId", JsonPrimitive(FIREBASE_TEST_APPLICATION_ID))
    put("instrumentationTargetPackage", JsonPrimitive(FIREBASE_APPLICATION_ID))
    put("builtReleaseAarFileName", JsonPrimitive(FIREBASE_RELEASE_AAR))
    put("releaseAarSha256", JsonPrimitive(values.releaseAarSha256))
    put("appBundledRuntimeSha256", JsonPrimitive(values.appBundledRuntimeSha256))
    put("aarBundledRuntimeSha256", JsonPrimitive(values.aarBundledRuntimeSha256))
}

internal fun validateFirebaseAndroidEvidence(evidence: JsonObject, expectedCommit: String): List<String> = buildList {
    if (evidence.keys != FIREBASE_ANDROID_EVIDENCE_KEYS) add("schema fields mismatch")
    if (evidence.intOrNull("schemaVersion") != 3) add("schema version is not 3")
    if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
    if (evidence.stringOrNull("commitSha") != expectedCommit) add("commit SHA mismatch")
    if (evidence.stringOrNull("result") != "passed") add("result is not passed")
    if (!evidence.stringOrNull("firebaseMatrixId").orEmpty().matches(Regex("matrix-[A-Za-z0-9_-]+"))) {
        add("Firebase matrix ID is invalid")
    }
    if (evidence.stringOrNull("firebaseProjectId").isNullOrBlank()) add("Firebase project ID is missing")
    if (!evidence.stringOrNull("firebaseResultsUri").orEmpty().startsWith("gs://")) {
        add("Firebase results URI is invalid")
    }
    expectedStrings().forEach { (key, value) ->
        if (evidence.stringOrNull(key) != value) add("$key mismatch")
    }
    if (evidence.intOrNull("deviceApi") != FIREBASE_DEVICE_API) add("device API mismatch")
    if (evidence.intOrNull("testsRun") != REQUIRED_ANDROID_RUNTIME_TESTS.size) add("test count mismatch")
    listOf(
        "firebaseMatrixSha256", "testReportSha256", "applicationApkSha256", "testApkSha256",
        "releaseAarSha256", "appBundledRuntimeSha256", "aarBundledRuntimeSha256",
    ).forEach { key -> if (!isFirebaseSha256(evidence.stringOrNull(key))) add("$key is invalid") }
    if (evidence.stringOrNull("applicationApkSha256") == evidence.stringOrNull("testApkSha256")) {
        add("application and test APK hashes must differ")
    }
    if (evidence.stringOrNull("appBundledRuntimeSha256") !=
        evidence.stringOrNull("aarBundledRuntimeSha256")
    ) {
        add("application APK and AAR runtime hashes differ")
    }
}

private fun expectedStrings(): Map<String, String> = mapOf(
    "firebaseMatrixFileName" to FIREBASE_MATRIX_FILE,
    "deviceModel" to FIREBASE_DEVICE_MODEL,
    "deviceArchitecture" to FIREBASE_DEVICE_ARCHITECTURE,
    "deviceLocale" to FIREBASE_DEVICE_LOCALE,
    "deviceOrientation" to FIREBASE_DEVICE_ORIENTATION,
    "testClassName" to ANDROID_RUNTIME_TEST_CLASS,
    "testReportFileName" to FIREBASE_ANDROID_REPORT,
    "testedApplicationKind" to FIREBASE_TESTED_APPLICATION_KIND,
    "applicationApkFileName" to FIREBASE_APPLICATION_APK,
    "testApkFileName" to FIREBASE_TEST_APK,
    "applicationId" to FIREBASE_APPLICATION_ID,
    "testApplicationId" to FIREBASE_TEST_APPLICATION_ID,
    "instrumentationTargetPackage" to FIREBASE_APPLICATION_ID,
    "builtReleaseAarFileName" to FIREBASE_RELEASE_AAR,
)

private fun JsonObject.stringOrNull(key: String): String? = runCatching { releaseString(key) }.getOrNull()
private fun JsonObject.intOrNull(key: String): Int? = runCatching { releaseInt(key) }.getOrNull()
private fun isFirebaseSha256(value: String?): Boolean = value.orEmpty().matches(Regex("[0-9a-f]{64}"))
internal fun firebaseEvidenceFile(directory: File, key: String, evidence: JsonObject): File =
    safePayloadFile(directory, evidence.releaseString(key))
