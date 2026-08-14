import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal fun generateIosPrivacyEvidence(
    xcframework: File,
    archivedApplication: File,
    manifest: File,
    dataFlowReview: File,
    policyFile: File,
    evidenceFile: File,
    tools: JsonArray,
    inspect: (File) -> IosPrivacySignals,
) {
    policyFile.atomicWriteJson(IosPrivacyPolicy.json())
    val slices = listOf("ios-arm64", "ios-arm64-simulator").map { slice ->
        val archive = xcframework.resolve("$slice/CodexAgent.framework/CodexAgent")
        check(archive.isFile) { "iOS privacy archive is missing: $archive" }
        scanIosPrivacyArchive(slice, archive, inspect)
    }
    val declarations = readPrivacyManifestDeclarations(manifest)
    val ambiguous = slices.ambiguousOccurrences()
    val frameworkManifests = manifestRecords(xcframework, xcframework)
    val applications = archivedApplication.resolve("Products/Applications")
    val archivedManifests = manifestRecords(applications, applications)
    evidenceFile.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("scope", JsonPrimitive("every static archive object occurrence in both XCFramework slices"))
        put("policySha256", JsonPrimitive(policyFile.releaseDigest()))
        put("manifest", buildJsonObject {
            put("file", manifest.releaseRecord("PrivacyInfo.xcprivacy"))
            put("declaredRequiredReasonApis", declarations.jsonDeclarations())
        })
        put("dataFlowReview", dataFlowReview.releaseRecord("privacy-data-flow-review.json"))
        put("tools", tools)
        put("detectedRequiredReasonCategories", slices.detectedCategories().jsonStrings())
        put("ambiguousRequiredReasonApis", buildJsonArray {
            ambiguous.forEach { (symbol, occurrences) -> add(buildJsonObject {
                put("symbol", JsonPrimitive(symbol))
                put("possibleCategories", IosPrivacyPolicy.ambiguousSymbols.getValue(symbol).jsonStrings())
                put("occurrences", occurrences)
            }) }
        })
        put("frameworkManifests", frameworkManifests)
        put("archivedSampleApplicationManifests", archivedManifests)
        put("slices", buildJsonArray { slices.forEach { add(it.json()) } })
    })
}

private fun Map<String, List<String>>.jsonDeclarations(): JsonArray = buildJsonArray {
    forEach { (category, reasons) -> add(buildJsonObject {
        put("category", JsonPrimitive(category))
        put("reasons", reasons.jsonStrings())
    }) }
}

private fun manifestRecords(root: File, relativeTo: File): JsonArray = buildJsonArray {
    if (!root.isDirectory) return@buildJsonArray
    root.walkTopDown().filter { it.isFile && it.name == "PrivacyInfo.xcprivacy" }
        .sortedBy { it.relativeTo(relativeTo).invariantSeparatorsPath }
        .forEach { manifest -> add(buildJsonObject {
            put("path", JsonPrimitive(manifest.relativeTo(relativeTo).invariantSeparatorsPath))
            put("bytes", JsonPrimitive(manifest.length()))
            put("sha256", JsonPrimitive(manifest.releaseDigest()))
        }) }
}
