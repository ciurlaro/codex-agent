import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal val candidateFirebaseAndroidEvidenceFileNames =
    protectedFirebaseAndroidRuntimeRawFiles.map(Pair<String, String>::second) +
        FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE

internal fun verifyCandidateRuntimeEvidence(input: CandidateInputFiles) {
    val desktopErrors = validateDesktopRuntimeEvidence(
        input.desktopEvidence,
        input.commit,
        input.version,
        input.mavenInventory,
        input.desktopDistributionManifest,
        input.desktopClassifierArchives,
    )
    check(desktopErrors.isEmpty()) {
        "Desktop runtime evidence is invalid: ${desktopErrors.joinToString()}"
    }
    val jvmErrors = validateJvmRuntimeEvidence(
        input.jvmEvidence,
        input.commit,
        input.desktopDistributionManifest,
        input.desktopClassifierArchives,
        input.jvmRuntimeRunner,
    )
    check(jvmErrors.isEmpty()) { "JVM runtime evidence is invalid: ${jvmErrors.joinToString()}" }
    listOf(
        Triple(NODE_RUNTIME_JS_BACKEND, input.nodeEvidence, input.nodeRuntimeRunner),
        Triple(NODE_RUNTIME_WASM_BACKEND, input.nodeWasmEvidence, input.nodeWasmRuntimeRunner),
    ).forEach { (backend, evidence, runner) ->
        val errors = validateNodeRuntimeEvidence(
            evidence,
            input.commit,
            backend,
            input.desktopDistributionManifest,
            input.desktopClassifierArchives,
            runner,
        )
        check(errors.isEmpty()) {
            "Node $backend runtime evidence is invalid: ${errors.joinToString()}"
        }
    }
    verifyCandidateFirebaseAndroidEvidence(input.androidEvidence, input.commit)
    verifyCandidateCentralAndroidRuntimeBinding(input.androidEvidence, input.centralBundle, input.version)
}

internal fun verifyCandidateFirebaseAndroidEvidence(files: List<File>, expectedCommit: String) {
    val byName = files.associateBy(File::getName)
    check(files.size == candidateFirebaseAndroidEvidenceFileNames.size &&
        byName.keys == candidateFirebaseAndroidEvidenceFileNames.toSet() &&
        files.all(File::isFile)) {
        "Firebase Android candidate evidence file set is invalid"
    }
    val evidenceFile = byName.getValue(FIREBASE_ANDROID_EVIDENCE_FILE)
    val evidence = evidenceFile.readReleaseObject()
    val errors = validateFirebaseAndroidEvidence(evidence, expectedCommit)
    check(errors.isEmpty()) { "Firebase Android runtime evidence is invalid: ${errors.joinToString()}" }

    val receipt = byName.getValue(FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE).readReleaseObject()
    check(receipt.keys == setOf(
        "schemaVersion", "result", "evidenceSha256", "firebaseMatrixSha256", "testReportSha256",
        "applicationApkSha256", "testApkSha256", "releaseAarSha256", "bundledRuntimeSha256",
    ) && receipt.releaseInt("schemaVersion") == 1 && receipt.releaseString("result") == "passed") {
        "Firebase Android verification receipt is invalid"
    }
    check(receipt.releaseString("evidenceSha256") == evidenceFile.releaseDigest()) {
        "Firebase Android verification receipt does not bind the evidence record"
    }
    listOf(
        Triple(FIREBASE_MATRIX_FILE, "firebaseMatrixSha256", "firebaseMatrixSha256"),
        Triple(FIREBASE_ANDROID_REPORT, "testReportSha256", "testReportSha256"),
        Triple(FIREBASE_APPLICATION_APK, "applicationApkSha256", "applicationApkSha256"),
        Triple(FIREBASE_TEST_APK, "testApkSha256", "testApkSha256"),
        Triple(FIREBASE_RELEASE_AAR, "releaseAarSha256", "releaseAarSha256"),
    ).forEach { (fileName, evidenceKey, receiptKey) ->
        val digest = byName.getValue(fileName).releaseDigest()
        check(evidence.releaseString(evidenceKey) == digest && receipt.releaseString(receiptKey) == digest) {
            "Firebase Android $fileName is not hash-bound"
        }
    }
    val runtime = receipt.releaseString("bundledRuntimeSha256")
    check(runtime == evidence.releaseString("appBundledRuntimeSha256") &&
        runtime == evidence.releaseString("aarBundledRuntimeSha256")) {
        "Firebase Android receipt does not bind the bundled runtime"
    }
}

internal fun verifyCandidateCentralAndroidRuntimeBinding(
    androidEvidence: List<File>,
    centralBundle: File,
    version: String,
) {
    val evidence = androidEvidence.single { it.name == FIREBASE_ANDROID_EVIDENCE_FILE }.readReleaseObject()
    val expectedRuntime = evidence.releaseString("appBundledRuntimeSha256")
    val expectedAar = evidence.releaseString("releaseAarSha256")
    check(expectedRuntime == evidence.releaseString("aarBundledRuntimeSha256")) {
        "Firebase Android runtime hashes differ"
    }
    val aarPath = "io/github/ciurlaro/codex-agent-runtime-android/$version/" +
        "codex-agent-runtime-android-$version.aar"
    val (stagedAar, stagedRuntime) = ZipFile(centralBundle).use { central ->
        val entries = central.entries().asSequence().filter { !it.isDirectory && it.name == aarPath }.toList()
        check(entries.size == 1) { "Central bundle must contain the exact Android AAR" }
        val entry = entries.single()
        val aarSha256 = central.getInputStream(entry).use { it.releaseDigest() }
        val runtimeSha256 = central.getInputStream(entry).use { input ->
            ZipInputStream(input).use { aar ->
                var digest: String? = null
                while (true) {
                    val entry = aar.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == AAR_RUNTIME_ENTRY) {
                        check(digest == null) { "Central Android AAR contains duplicate runtime entries" }
                        digest = aar.releaseDigest()
                    }
                }
                checkNotNull(digest) { "Central Android AAR runtime is missing" }
            }
        }
        aarSha256 to runtimeSha256
    }
    check(stagedAar == expectedAar) {
        "Central Android AAR is not the exact Firebase-evidenced release AAR"
    }
    check(stagedRuntime == expectedRuntime) {
        "Central Android AAR does not contain the Firebase-evidenced ARM64 runtime"
    }
}
