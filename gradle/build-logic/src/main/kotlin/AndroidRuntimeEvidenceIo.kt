import java.io.File
import java.util.zip.ZipFile
import org.w3c.dom.Element

internal data class AndroidTestReport(
    val file: File,
    val testCases: List<AndroidTestCase>,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
)

internal data class AndroidTestCase(val className: String, val methodName: String)

private val REQUIRED_ANDROID_RUNTIME_CASES = REQUIRED_ANDROID_RUNTIME_TESTS
    .mapTo(mutableSetOf()) { AndroidTestCase(ANDROID_RUNTIME_TEST_CLASS, it) }

internal fun findPassingAndroidRuntimeReport(directory: File): AndroidTestReport {
    val parsed = directory.walkTopDown()
        .filter { it.isFile && it.extension == "xml" }
        .mapNotNull { runCatching { parseAndroidTestReport(it) }.getOrNull() }
        .filter { it.testCases.toSet() == REQUIRED_ANDROID_RUNTIME_CASES }
        .toList()
    check(parsed.size == 1) { "Expected exactly one RuntimeBootstrapDeviceTest report, found ${parsed.size}" }
    return parsed.single().also(::requirePassingAndroidRuntimeReport)
}

internal fun requirePassingAndroidRuntimeReport(file: File): AndroidTestReport =
    parseAndroidTestReport(file).also(::requirePassingAndroidRuntimeReport)

private fun requirePassingAndroidRuntimeReport(report: AndroidTestReport) {
    check(report.testCases.size == REQUIRED_ANDROID_RUNTIME_CASES.size) { "Android runtime test count mismatch" }
    check(report.testCases.toSet() == REQUIRED_ANDROID_RUNTIME_CASES) {
        "Required Android runtime test class and methods did not run"
    }
    check(report.failures == 0 && report.errors == 0 && report.skipped == 0) {
        "Android runtime instrumentation did not pass cleanly"
    }
}

internal fun parseAndroidTestReport(file: File): AndroidTestReport {
    val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(file)
    val cases = document.getElementsByTagName("testcase")
    val parsedCases = (0 until cases.length).map { index ->
        val element = cases.item(index) as Element
        AndroidTestCase(
            element.getAttribute("classname"),
            element.getAttribute("name").removeSuffix("()"),
        )
    }
    return AndroidTestReport(
        file,
        parsedCases,
        document.getElementsByTagName("failure").length,
        document.getElementsByTagName("error").length,
        document.getElementsByTagName("skipped").length,
    )
}

internal fun File.singleZipEntryDigest(path: String): String = ZipFile(this).use { archive ->
    val matches = mutableListOf<java.util.zip.ZipEntry>()
    val entries = archive.entries()
    while (entries.hasMoreElements()) {
        entries.nextElement().takeIf { !it.isDirectory && it.name == path }?.let(matches::add)
    }
    check(matches.size == 1) { "Expected exactly one ZIP member $path in $name" }
    archive.getInputStream(matches.single()).use { it.releaseDigest() }
}

internal fun parseAndroidManifestIdentity(xml: String): AndroidManifestIdentity {
    val applicationId = Regex("""\bpackage="([^"]+)"""").find(xml)?.groupValues?.get(1)
        ?: error("Android manifest package is missing")
    val target = Regex("""android:targetPackage="([^"]+)"""").find(xml)?.groupValues?.get(1)
        ?: error("Android instrumentation target package is missing")
    return AndroidManifestIdentity(applicationId, target)
}
