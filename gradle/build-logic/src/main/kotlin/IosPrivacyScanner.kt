import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal data class IosPrivacyMemberScan(
    val member: IosArMember,
    val sha256: String,
    val dependency: String?,
    val hits: List<IosPrivacyHit>,
    val ambiguous: Set<String>,
)

internal data class IosPrivacySliceScan(
    val slice: String,
    val archive: File,
    val members: List<IosPrivacyMemberScan>,
) {
    fun json(): JsonObject {
        val duplicates = members.groupingBy { it.member.name }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        val dependencies = members.filter { it.dependency != null }.groupBy { it.dependency!! }
        return buildJsonObject {
            put("slice", JsonPrimitive(slice))
            put("archive", archive.releaseRecord("CodexAgent.framework/CodexAgent"))
            put("archiveMemberCount", JsonPrimitive(members.size))
            put("objectMemberCount", JsonPrimitive(members.count { it.member.kind == IosArMemberKind.OBJECT }))
            put("duplicateArchiveMemberNames", duplicates.jsonStrings())
            put("members", buildJsonArray { members.forEach { add(it.json()) } })
            put("dependencies", buildJsonArray {
                dependencies.toSortedMap().forEach { (name, records) -> add(buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("memberCount", JsonPrimitive(records.size))
                    put("bytes", JsonPrimitive(records.sumOf { it.member.bytes }))
                    put("requiredReasonCategories", records.flatMap { it.hits }.mapTo(sortedSetOf()) { it.category }.jsonStrings())
                }) }
            })
        }
    }
}

internal fun scanIosPrivacyArchive(
    slice: String,
    archive: File,
    inspect: (File) -> IosPrivacySignals,
): IosPrivacySliceScan {
    val parsed = IosStaticArchive(archive)
    val temporary = Files.createTempDirectory("codex-privacy-$slice-").toFile()
    return try {
        val objectFile = temporary.resolve("member.o")
        val members = parsed.members.map { member ->
            if (member.kind == IosArMemberKind.INDEX) {
                IosPrivacyMemberScan(member, parsed.copyAndHash(member), null, emptyList(), emptySet())
            } else {
                objectFile.outputStream().buffered().use { output -> parsed.copyAndHash(member, output) }
                    .let { digest ->
                        val (hits, ambiguous) = IosPrivacyPolicy.classify(inspect(objectFile))
                        IosPrivacyMemberScan(member, digest, privacyDependency(member.name), hits, ambiguous)
                    }
            }
        }
        IosPrivacySliceScan(slice, archive, members)
    } finally {
        temporary.deleteRecursively()
    }
}

private fun IosPrivacyMemberScan.json(): JsonObject = buildJsonObject {
    put("index", JsonPrimitive(member.index))
    put("name", JsonPrimitive(member.name))
    put("kind", JsonPrimitive(member.kind.name.lowercase()))
    put("bytes", JsonPrimitive(member.bytes))
    put("sha256", JsonPrimitive(sha256))
    dependency?.let { put("dependency", JsonPrimitive(it)) }
    put("requiredReasonApis", buildJsonArray { hits.forEach { hit -> add(buildJsonObject {
        put("category", JsonPrimitive(hit.category))
        put("source", JsonPrimitive(hit.source))
        put("token", JsonPrimitive(hit.token))
    }) } })
    put("ambiguousRequiredReasonApis", ambiguous.jsonStrings())
}

private fun privacyDependency(member: String): String {
    var stem = when {
        member.endsWith(".rcgu.o") -> member.removeSuffix(".rcgu.o")
        member.endsWith(".o") -> member.removeSuffix(".o")
        else -> member
    }
    stem = stem.substringBefore('.')
    return stem.replace(Regex("-[0-9a-f]{16}$"), "")
}

internal fun List<IosPrivacySliceScan>.detectedCategories(): Set<String> =
    flatMap { it.members }.flatMap { it.hits }.mapTo(sortedSetOf()) { it.category }

internal fun List<IosPrivacySliceScan>.ambiguousOccurrences(): Map<String, JsonArray> =
    IosPrivacyPolicy.ambiguousSymbols.keys.mapNotNull { symbol ->
        val occurrences = buildJsonArray {
            this@ambiguousOccurrences.forEach { slice ->
                slice.members.filter { symbol in it.ambiguous }.forEach { member -> add(buildJsonObject {
                    put("slice", JsonPrimitive(slice.slice))
                    put("memberIndex", JsonPrimitive(member.member.index))
                    put("member", JsonPrimitive(member.member.name))
                }) }
            }
        }
        symbol.takeIf { occurrences.isNotEmpty() }?.let { it to occurrences }
    }.toMap()
