import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun resolveSingleApk(metadataFile: File, description: String): File {
    val elements = metadataFile.readReleaseObject().releaseArray("elements")
    check(elements.size == 1) { "Expected exactly one $description APK output" }
    val output = (elements.single() as? JsonObject)?.releaseString("outputFile")
        ?: error("$description APK output is missing")
    return safePayloadFile(metadataFile.parentFile, output).also {
        check(it.isFile) { "$description APK is missing: ${it.name}" }
    }
}

internal fun parseFirebaseTestMatrix(file: File): FirebaseTestMatrix {
    val matrix = file.readReleaseObject()
    check(matrix.releaseString("state") == "FINISHED") { "Firebase matrix did not finish" }
    val executions = matrix.array("testExecutions")
    check(executions.size == 1) { "Firebase evidence requires exactly one test execution" }
    val execution = executions.single().objectValue("test execution")
    check(execution.releaseString("state") == "FINISHED") { "Firebase test execution did not finish" }
    val outcome = execution.objectValue("toolExecution").objectValue("outcome").releaseString("summary")
    check(outcome == "SUCCESS") { "Firebase test execution did not pass: $outcome" }
    val device = execution.objectValue("environment").objectValue("androidDevice")
    val parsed = FirebaseTestMatrix(
        matrix.releaseString("testMatrixId"),
        matrix.releaseString("projectId"),
        matrix.objectValue("resultStorage").objectValue("googleCloudStorage").releaseString("gcsPath"),
        device.releaseString("androidModelId"),
        device.releaseString("androidVersionId").toIntOrNull() ?: error("Firebase device API is invalid"),
        device.releaseString("locale"),
        device.releaseString("orientation"),
    )
    check(parsed.id.matches(Regex("matrix-[A-Za-z0-9_-]+"))) { "Firebase matrix ID is invalid" }
    check(parsed.projectId.isNotBlank()) { "Firebase project ID is missing" }
    check(parsed.resultsUri.startsWith("gs://")) { "Firebase results URI is invalid" }
    check(parsed.model == FIREBASE_DEVICE_MODEL) { "Firebase device model mismatch" }
    check(parsed.api == FIREBASE_DEVICE_API) { "Firebase device API mismatch" }
    check(parsed.locale == FIREBASE_DEVICE_LOCALE) { "Firebase device locale mismatch" }
    check(parsed.orientation == FIREBASE_DEVICE_ORIENTATION) { "Firebase device orientation mismatch" }
    return parsed
}

internal fun parseAndroidApplicationId(xml: String): String =
    Regex("""\bpackage="([^"]+)"""").find(xml)?.groupValues?.get(1)
        ?: error("Android manifest package is missing")

internal fun verifyFirebaseAndroidRuntimeEvidenceArtifacts(
    evidenceFile: File,
    evidenceDirectory: File,
    expectedCommit: String,
    pinnedRuntimeSha256: String,
    applicationId: (File) -> String,
    testIdentity: (File) -> AndroidManifestIdentity,
): FirebaseAndroidEvidenceVerification {
    val evidence = evidenceFile.readReleaseObject()
    val errors = validateFirebaseAndroidEvidence(evidence, expectedCommit)
    check(errors.isEmpty()) { "Firebase Android runtime evidence is invalid: ${errors.joinToString()}" }
    check(pinnedRuntimeSha256.matches(Regex("[0-9a-f]{64}"))) { "Pinned Android runtime SHA-256 is invalid" }

    val matrix = firebaseEvidenceFile(evidenceDirectory, "firebaseMatrixFileName", evidence)
    check(matrix.isFile && matrix.releaseDigest() == evidence.releaseString("firebaseMatrixSha256")) {
        "Firebase matrix hash mismatch"
    }
    val parsedMatrix = parseFirebaseTestMatrix(matrix)
    check(parsedMatrix.id == evidence.releaseString("firebaseMatrixId")) { "Firebase matrix ID mismatch" }
    check(parsedMatrix.projectId == evidence.releaseString("firebaseProjectId")) { "Firebase project mismatch" }
    check(parsedMatrix.resultsUri == evidence.releaseString("firebaseResultsUri")) { "Firebase results URI mismatch" }

    val report = firebaseEvidenceFile(evidenceDirectory, "testReportFileName", evidence)
    check(report.isFile && report.releaseDigest() == evidence.releaseString("testReportSha256")) {
        "Firebase JUnit report hash mismatch"
    }
    requirePassingAndroidRuntimeReport(report)

    val app = firebaseEvidenceFile(evidenceDirectory, "applicationApkFileName", evidence)
    val test = firebaseEvidenceFile(evidenceDirectory, "testApkFileName", evidence)
    val aar = firebaseEvidenceFile(evidenceDirectory, "builtReleaseAarFileName", evidence)
    check(app.isFile && app.releaseDigest() == evidence.releaseString("applicationApkSha256")) {
        "Android application APK hash mismatch"
    }
    check(test.isFile && test.releaseDigest() == evidence.releaseString("testApkSha256")) {
        "Android test APK hash mismatch"
    }
    check(aar.isFile && aar.releaseDigest() == evidence.releaseString("releaseAarSha256")) {
        "Android release AAR hash mismatch"
    }
    check(applicationId(app) == FIREBASE_APPLICATION_ID) { "Android host application ID mismatch" }
    val identity = testIdentity(test)
    check(identity.applicationId == FIREBASE_TEST_APPLICATION_ID) { "Android test application ID mismatch" }
    check(identity.instrumentationTargetPackage == FIREBASE_APPLICATION_ID) {
        "Android instrumentation target mismatch"
    }

    val appRuntime = app.singleZipEntryDigest(APK_RUNTIME_ENTRY)
    val aarRuntime = aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY)
    check(appRuntime == evidence.releaseString("appBundledRuntimeSha256")) { "Application runtime hash mismatch" }
    check(aarRuntime == evidence.releaseString("aarBundledRuntimeSha256")) { "AAR runtime hash mismatch" }
    check(appRuntime == aarRuntime && appRuntime == pinnedRuntimeSha256) { "Bundled Android runtime is not pinned" }

    return FirebaseAndroidEvidenceVerification(
        evidenceFile.releaseDigest(), matrix.releaseDigest(), report.releaseDigest(), app.releaseDigest(),
        test.releaseDigest(), aar.releaseDigest(), appRuntime,
    )
}

private fun JsonObject.objectValue(key: String): JsonObject =
    this[key] as? JsonObject ?: error("Firebase matrix $key object is missing")
private fun JsonObject.array(key: String): JsonArray =
    this[key] as? JsonArray ?: error("Firebase matrix $key array is missing")
private fun kotlinx.serialization.json.JsonElement.objectValue(description: String): JsonObject =
    this as? JsonObject ?: error("Firebase matrix $description is invalid")
