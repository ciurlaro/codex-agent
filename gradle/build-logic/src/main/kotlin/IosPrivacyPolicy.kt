import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal data class IosPrivacyCategory(
    val name: String,
    val allowedReasons: Set<String>,
    val approvedReasons: Set<String> = emptySet(),
    val symbols: Set<String> = emptySet(),
    val strings: Set<String> = emptySet(),
)

internal data class IosPrivacyHit(val category: String, val source: String, val token: String)
internal data class IosPrivacySignals(val symbols: Set<String>, val strings: Set<String>)

internal object IosPrivacyPolicy {
    const val reference =
        "https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/" +
            "nsprivacyaccessedapitypes/nsprivacyaccessedapitype"

    val categories = listOf(
        IosPrivacyCategory(
            "NSPrivacyAccessedAPICategoryActiveKeyboards",
            setOf("3EC4.1", "54BD.1"),
            strings = setOf("activeInputModes"),
        ),
        IosPrivacyCategory(
            "NSPrivacyAccessedAPICategoryDiskSpace",
            setOf("85F4.1", "E174.1", "7D9E.1", "B728.1"),
            symbols = setOf("_fstatfs", "_statfs", "_fstatvfs", "_statvfs"),
            strings = setOf(
                "volumeAvailableCapacityKey", "volumeTotalCapacityKey",
                "volumeAvailableCapacityForImportantUsageKey", "volumeAvailableCapacityForOpportunisticUsageKey",
                "volumeTotalCapacityForImportantUsageKey", "volumeTotalCapacityForOpportunisticUsageKey",
                "systemFreeSize", "systemSize", "attributesOfFileSystemForPath:",
            ),
        ),
        IosPrivacyCategory(
            "NSPrivacyAccessedAPICategoryFileTimestamp",
            setOf("DDA9.1", "C617.1", "3B52.1", "0A2A.1"),
            approvedReasons = setOf("C617.1", "3B52.1"),
            symbols = setOf("_fstat", "_fstatat", "_lstat", "_stat"),
            strings = setOf(
                "creationDate", "modificationDate", "contentModificationDate", "fileModificationDate",
                "NSURLContentModificationDateKey", "NSURLCreationDateKey", "attributesOfItemAtPath:",
            ),
        ),
        IosPrivacyCategory(
            "NSPrivacyAccessedAPICategorySystemBootTime",
            setOf("35F9.1", "8FFB.1", "3D61.1"),
            symbols = setOf("_mach_absolute_time"),
            strings = setOf("systemUptime"),
        ),
        IosPrivacyCategory(
            "NSPrivacyAccessedAPICategoryUserDefaults",
            setOf("CA92.1", "1C8F.1", "C56D.1", "AC6B.1"),
            strings = setOf("NSUserDefaults", "CFPreferences", "standardUserDefaults"),
        ),
    ).sortedBy { it.name }

    val ambiguousSymbols = sortedMapOf(
        "_fgetattrlist" to setOf(
            "NSPrivacyAccessedAPICategoryDiskSpace", "NSPrivacyAccessedAPICategoryFileTimestamp",
        ),
        "_getattrlist" to setOf(
            "NSPrivacyAccessedAPICategoryDiskSpace", "NSPrivacyAccessedAPICategoryFileTimestamp",
        ),
        "_getattrlistat" to setOf(
            "NSPrivacyAccessedAPICategoryDiskSpace", "NSPrivacyAccessedAPICategoryFileTimestamp",
        ),
        "_getattrlistbulk" to setOf("NSPrivacyAccessedAPICategoryFileTimestamp"),
    )

    val stringTokens = categories.flatMapTo(sortedSetOf()) { it.strings }

    fun classify(signals: IosPrivacySignals): Pair<List<IosPrivacyHit>, Set<String>> {
        val hits = categories.flatMap { category ->
            category.symbols.filter(signals.symbols::contains).map {
                IosPrivacyHit(category.name, "undefined-symbol", it)
            } + category.strings.filter(signals.strings::contains).map {
                IosPrivacyHit(category.name, "string", it)
            }
        }.sortedWith(compareBy(IosPrivacyHit::category, IosPrivacyHit::source, IosPrivacyHit::token))
        return hits to signals.symbols.filterTo(sortedSetOf()) { it in ambiguousSymbols }
    }

    fun json(): JsonObject = buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("appleReference", JsonPrimitive(reference))
        put("categories", buildJsonArray {
            categories.forEach { category -> add(buildJsonObject {
                put("category", JsonPrimitive(category.name))
                put("allowedReasons", category.allowedReasons.jsonStrings())
                put("approvedReasons", category.approvedReasons.jsonStrings())
                put("undefinedSymbols", category.symbols.jsonStrings())
                put("strings", category.strings.jsonStrings())
            }) }
        })
        put("ambiguousUndefinedSymbols", buildJsonArray {
            ambiguousSymbols.forEach { (symbol, possible) -> add(buildJsonObject {
                put("symbol", JsonPrimitive(symbol))
                put("possibleCategories", possible.jsonStrings())
            }) }
        })
    }
}

internal fun Collection<String>.jsonStrings(): JsonArray = buildJsonArray {
    sorted().forEach { add(JsonPrimitive(it)) }
}
