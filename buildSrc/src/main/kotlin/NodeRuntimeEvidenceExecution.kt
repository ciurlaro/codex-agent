import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class NodeEvidenceProcessResult(val exitCode: Int, val output: String)

internal fun executeNodeRuntimeEvidence(
    candidateCommit: String,
    target: String,
    runnerOs: String,
    runnerArch: String,
    nodeExecutable: String,
    distributionManifest: File,
    classifierArchive: File,
    compiledNodeTestRuntime: File,
    windowsSupervisor: File?,
    evidenceFile: File,
    testReport: File,
    runner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult = ::runNodeEvidenceProcess,
) {
    evidenceFile.delete()
    testReport.delete()
    check(candidateCommit.matches(Regex("[0-9a-f]{40}"))) { "Node evidence commit is not immutable" }
    val expected = desktopRuntimeEvidenceTargets.getValue(target)
    check(runnerOs == expected.runnerOs && runnerArch == expected.runnerArch) {
        "Node evidence runner does not match $target"
    }
    check(evidenceFile.name == nodeRuntimeEvidenceFileName(target)) { "Node evidence filename mismatch" }
    check(testReport.name == nodeRuntimeTestReportFileName(target)) { "Node test report filename mismatch" }
    check(nodeExecutable.isNotBlank() && '\n' !in nodeExecutable && '\r' !in nodeExecutable) {
        "Node executable is invalid"
    }
    check(compiledNodeTestRuntime.isFile && compiledNodeTestRuntime.length() > 0) {
        "Compiled Node test/runtime artifact is missing"
    }
    if (target == "mingwX64") {
        check(windowsSupervisor?.isFile == true && windowsSupervisor.length() > 0) {
            "Windows Node evidence requires the supervisor artifact"
        }
    } else {
        check(windowsSupervisor == null) { "A Windows supervisor was supplied for $target" }
    }

    val manifest = readDesktopCodexManifest(distributionManifest)
    val classifier = inspectNodeClassifier(target, manifest, classifierArchive)
    val version = runner(listOf(nodeExecutable, "--version"), emptyMap())
    check(version.exitCode == 0 && version.output.trim().replace("\r", "") == "v$PINNED_NODE_VERSION") {
        "Node evidence requires exactly Node v$PINNED_NODE_VERSION"
    }

    val temporary = Files.createTempDirectory("codex-agent-node-evidence-$target").toFile()
    try {
        val appServer = temporary.resolve(classifier.executableName)
        ZipFile(classifierArchive).use { zip ->
            zip.getInputStream(zip.getEntry(classifier.executableName)).use { input ->
                Files.copy(input, appServer.toPath(), REPLACE_EXISTING)
            }
        }
        check(appServer.releaseDigest() == classifier.binarySha256) { "Extracted Node App Server hash mismatch" }
        if (target != "mingwX64") check(appServer.setExecutable(true, false)) {
            "Node App Server could not be made executable"
        }
        val runnerEntry = extractNodeRuntimeRunner(compiledNodeTestRuntime, temporary.resolve("runner"))
        val environment = mutableMapOf(
            "CODEX_AGENT_APP_SERVER_EXECUTABLE" to appServer.absolutePath,
            "CODEX_AGENT_DESKTOP_TARGET" to target,
        )
        windowsSupervisor?.let { environment["CODEX_AGENT_WINDOWS_SUPERVISOR"] = it.absolutePath }
        val listing = runner(
            listOf(nodeExecutable, runnerEntry.absolutePath, "--list-tests"),
            environment,
        )
        check(listing.exitCode == 0) { "Node test discovery failed: ${listing.output}" }
        verifyNodeTestListing(listing.output)
        nodeRuntimeTestMethods.forEach { method ->
            val result = runner(
                listOf(
                    nodeExecutable,
                    runnerEntry.absolutePath,
                    "--run-test=$NODE_RUNTIME_TEST_CLASS.$method",
                ),
                environment,
            )
            check(result.exitCode == 0) { "Node runtime test failed ($method): ${result.output}" }
        }
        writeNodeRuntimeTestReport(testReport)
        verifyNodeRuntimeTestReport(testReport)
        evidenceFile.atomicWriteJson(buildNodeRuntimeEvidence(NodeRuntimeEvidenceValues(
            candidateCommit,
            target,
            classifier,
            compiledNodeTestRuntime,
            windowsSupervisor?.releaseDigest(),
        )))
    } finally {
        temporary.deleteRecursively()
    }
}

private fun extractNodeRuntimeRunner(archive: File, destination: File): File {
    val members = inspectNodeRuntimeRunnerArchive(archive)
    destination.mkdirs()
    ZipFile(archive).use { zip ->
        members.forEach { name ->
            zip.getInputStream(zip.getEntry(name)).use { input ->
                Files.copy(input, destination.resolve(name).toPath(), REPLACE_EXISTING)
            }
        }
    }
    return destination.resolve(NODE_RUNTIME_RUNNER_ENTRY)
}

internal fun verifyNodeTestListing(output: String) {
    val actual = output.replace("\r", "").lineSequence().filter(String::isNotBlank).toList()
    val expected = listOf("$NODE_RUNTIME_TEST_CLASS.") + nodeRuntimeTestMethods.map { "  $it" }
    check(actual == expected) { "Node test inventory is incomplete or unexpected" }
}

internal fun verifyNodeRuntimeTestReport(file: File) {
    val suite = nodeDocumentBuilderFactory().newDocumentBuilder().parse(file).documentElement
    check(suite.tagName == "testsuite") { "Node test report has no testsuite root" }
    check(suite.getAttribute("tests").toInt() == nodeRuntimeTestMethods.size &&
        suite.getAttribute("skipped").toInt() == 0 && suite.getAttribute("failures").toInt() == 0 &&
        suite.getAttribute("errors").toInt() == 0) {
        "Node runtime smoke must run every exact test without skips or failures"
    }
    val cases = suite.getElementsByTagName("testcase").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }
    check(cases.size == nodeRuntimeTestMethods.size &&
        cases.map { it.getAttribute("classname") }.toSet() == setOf(NODE_RUNTIME_TEST_CLASS) &&
        cases.map { it.getAttribute("name") }.toSet() == nodeRuntimeTestMethods) {
        "Node runtime test class or method inventory mismatch"
    }
    check(suite.getElementsByTagName("failure").length == 0 &&
        suite.getElementsByTagName("error").length == 0 &&
        suite.getElementsByTagName("skipped").length == 0) {
        "Node runtime test report contains a non-passing case"
    }
}

private fun writeNodeRuntimeTestReport(file: File) {
    file.parentFile.mkdirs()
    file.writeText(buildString {
        append("<testsuite tests=\"").append(nodeRuntimeTestMethods.size)
            .append("\" skipped=\"0\" failures=\"0\" errors=\"0\">\n")
        nodeRuntimeTestMethods.forEach { method ->
            append("  <testcase classname=\"").append(NODE_RUNTIME_TEST_CLASS)
                .append("\" name=\"").append(method).append("\"/>\n")
        }
        append("</testsuite>\n")
    })
}

private fun nodeDocumentBuilderFactory() = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}

private fun runNodeEvidenceProcess(
    command: List<String>,
    environment: Map<String, String>,
): NodeEvidenceProcessResult {
    val log = Files.createTempFile("node-runtime-evidence", ".log").toFile()
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { environment().putAll(environment) }
            .start()
        val completed = process.waitFor(5, TimeUnit.MINUTES)
        if (!completed) process.destroyForcibly().waitFor()
        NodeEvidenceProcessResult(if (completed) process.exitValue() else -1, log.readText())
    } finally {
        log.delete()
    }
}
