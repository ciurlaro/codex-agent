package io.github.codex_agent_labs.codexmobile.appserver.generator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ProtocolModels(root: JsonObject) {
    private val definitions = linkedMapOf<String, JsonObject>().apply {
        root.forEach { (name, schema) ->
            if (name != "v2") put(name, schema.jsonObject)
        }
        root.getValue("v2").jsonObject.forEach { (name, schema) -> putIfAbsent(name, schema.jsonObject) }
    }
    private val kinds = definitions.mapValues { (_, schema) -> kind(schema) }

    fun serializer(type: String): String = when (type) {
        "Unit" -> "Unit.serializer()"
        else -> when (kinds[type]) {
            ModelKind.JSON -> "kotlinx.serialization.json.JsonElement.serializer()"
            ModelKind.STRING -> "String.serializer()"
            ModelKind.LONG -> "Long.serializer()"
            ModelKind.DOUBLE -> "Double.serializer()"
            ModelKind.BOOLEAN -> "Boolean.serializer()"
            else -> "$type.serializer()"
        }
    }

    fun renderFiles(): List<GeneratedFile> {
        val definitions = definitions.toSortedMap().flatMap { (name, schema) ->
            renderDefinitionParts(name, schema).mapIndexed { index, definition ->
                "$name#${index + 1}" to (definition.trimEnd() + "\n")
            }
        }
        val chunks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        definitions.forEach { (name, definition) ->
            val candidateLines = MODEL_HEADER_LINES + (current + definition).sumOf(String::lineCount)
            if (current.isNotEmpty() && candidateLines > MAX_GENERATED_LINES) {
                chunks += current
                current = mutableListOf()
            }
            check(MODEL_HEADER_LINES + definition.lineCount() <= MAX_GENERATED_LINES) {
                "Generated model definition $name exceeds $MAX_GENERATED_LINES lines"
            }
            current += definition
            if (MODEL_HEADER_LINES + current.sumOf(String::lineCount) >= PREFERRED_GENERATED_LINES) {
                chunks += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) chunks += current
        rebalanceLastChunk(chunks)
        return chunks.mapIndexed { index, chunk ->
            val content = modelHeader() + chunk.joinToString("\n")
            check(content.lineCount() in MIN_GENERATED_LINES..MAX_GENERATED_LINES) {
                "Generated model shard ${index + 1} has ${content.lineCount()} lines"
            }
            GeneratedFile("GeneratedProtocolModels${(index + 1).toString().padStart(3, '0')}.kt", content)
        }
    }

    private fun renderDefinition(name: String, schema: JsonObject): String = when (kind(schema)) {
        ModelKind.ENUM -> renderEnum(name, schema)
        ModelKind.OBJECT -> renderObject(name, schema)
        ModelKind.UNION -> renderUnion(name, schema, checkNotNull(discriminatedUnion(schema)))
        ModelKind.STRING -> "internal typealias $name = String\n"
        ModelKind.LONG -> "internal typealias $name = Long\n"
        ModelKind.DOUBLE -> "internal typealias $name = Double\n"
        ModelKind.BOOLEAN -> "internal typealias $name = Boolean\n"
        ModelKind.JSON -> "internal typealias $name = JsonElement\n"
    }

    private fun renderDefinitionParts(name: String, schema: JsonObject): List<String> =
        discriminatedUnion(schema)?.let { renderUnionParts(name, schema, it) }
            ?: listOf(renderDefinition(name, schema))

    private fun renderEnum(name: String, schema: JsonObject): String = buildString {
        appendLine("@Serializable")
        appendLine("internal enum class $name {")
        val used = mutableSetOf<String>()
        schema.getValue("enum").jsonArray.forEachIndexed { index, raw ->
            val value = raw.jsonPrimitive.content
            var entry = value.enumEntryName()
            while (!used.add(entry)) entry = "${entry}_${index + 1}"
            appendLine("    @SerialName(\"${value.escape()}\") $entry,")
        }
        appendLine("}")
    }

    private fun renderObject(
        name: String,
        schema: JsonObject,
        parent: String? = null,
        discriminator: Pair<String, String>? = null,
    ): String {
        if (schema["additionalProperties"]?.toString() == "true" && schema["properties"] != null) {
            return "internal typealias $name = JsonObject\n"
        }
        val properties = schema["properties"]?.jsonObject.orEmpty()
        if (properties.isEmpty()) return buildString {
            appendLine("@Serializable")
            append("internal class $name")
            parent?.let { append(" : $it") }
            appendLine()
        }
        val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
        val fields = properties.map { (wireName, propertySchema) ->
            val forced = discriminator?.takeIf { it.first == wireName }?.second
            val optional = wireName !in required && forced == null
            val rawType = if (forced != null) "String" else (propertySchema as? JsonObject)?.let(::typeOf) ?: "JsonElement"
            Field(wireName, wireName.kotlinIdentifier(), rawType.nullableIf(optional), forced, optional)
        }.sortedBy { it.optional || it.forcedValue != null }
        return buildString {
            appendLine("@Serializable")
            appendLine("internal data class $name(")
            fields.forEach { field ->
                appendLine("    @SerialName(\"${field.wireName.escape()}\")")
                append("    public val ${field.identifier}: ${field.type}")
                when {
                    field.forcedValue != null -> append(" = \"${field.forcedValue.escape()}\"")
                    field.optional -> append(" = null")
                }
                appendLine(",")
            }
            append(")")
            parent?.let { append(" : $it") }
            if (discriminator == null) {
                appendLine()
            } else {
                val identifier = discriminator.first.kotlinIdentifier()
                appendLine(" {")
                appendLine("    init { require($identifier == \"${discriminator.second.escape()}\") }")
                appendLine("}")
            }
        }
    }

    private fun renderUnion(
        name: String,
        schema: JsonObject,
        union: DiscriminatedUnion,
    ): String = renderUnionParts(name, schema, union).joinToString("\n")

    private fun renderUnionParts(
        name: String,
        schema: JsonObject,
        union: DiscriminatedUnion,
    ): List<String> = buildList {
        add("@Serializable(with = ${name}Serializer::class)\ninternal sealed interface $name\n")
        union.variants.forEach { variant ->
            add(
                renderObject(
                    variant.name,
                    mergeUnionConstraints(schema, variant.schema),
                    name,
                    union.property to variant.value,
                ),
            )
        }
        add(buildString {
            appendLine("internal object ${name}Serializer : JsonContentPolymorphicSerializer<$name>($name::class) {")
            appendLine("    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<$name> =")
            appendLine("        when (element.jsonObject[\"${union.property.escape()}\"]?.jsonPrimitive?.content) {")
            union.variants.forEach { variant ->
                appendLine("            \"${variant.value.escape()}\" -> ${variant.name}.serializer()")
            }
            appendLine("            else -> error(\"Unknown $name ${union.property}\")")
            appendLine("        }")
            appendLine("}")
        })
    }

    private fun mergeUnionConstraints(schema: JsonObject, variant: JsonObject): JsonObject {
        val properties = schema["properties"]?.jsonObject.orEmpty() +
            variant["properties"]?.jsonObject.orEmpty()
        val required = (
            schema["required"]?.jsonArray.orEmpty() +
                variant["required"]?.jsonArray.orEmpty()
            ).distinctBy { it.jsonPrimitive.content }
        return JsonObject(variant.toMutableMap().apply {
            put("properties", JsonObject(properties))
            put("required", JsonArray(required))
        })
    }

    private fun kind(schema: JsonObject): ModelKind {
        if (schema["enum"] is JsonArray && schema["type"]?.toString()?.contains("string") == true) return ModelKind.ENUM
        if (discriminatedUnion(schema) != null) return ModelKind.UNION
        val types = schema.types()
        return when (types.firstOrNull { it != "null" }) {
            "object" -> if (schema["additionalProperties"]?.toString() == "true" && schema["properties"] != null) {
                ModelKind.JSON
            } else {
                ModelKind.OBJECT
            }
            "string" -> ModelKind.STRING
            "integer" -> ModelKind.LONG
            "number" -> ModelKind.DOUBLE
            "boolean" -> ModelKind.BOOLEAN
            else -> ModelKind.JSON
        }
    }

    private fun typeOf(schema: JsonObject): String {
        schema["\$ref"]?.jsonPrimitive?.content?.let { return it.substringAfterLast('/') }
        schema["allOf"]?.jsonArray?.singleOrNull()?.jsonObject?.let { return typeOf(it) }
        val nullableUnion = (schema["anyOf"] ?: schema["oneOf"]) as? JsonArray
        if (nullableUnion != null) {
            val variants = nullableUnion.mapNotNull { it as? JsonObject }
            if (variants.size != nullableUnion.size) return "JsonElement"
            val nonNull = variants.filterNot { it.types() == listOf("null") }
            if (nonNull.size == 1 && nonNull.size < nullableUnion.size) return typeOf(nonNull.single()).nullableIf(true)
            return "JsonElement"
        }
        val types = schema.types()
        val nullable = "null" in types
        val type = when (types.firstOrNull { it != "null" }) {
            "string" -> "String"
            "integer" -> "Long"
            "number" -> "Double"
            "boolean" -> "Boolean"
            "array" -> "List<${(schema["items"] as? JsonObject)?.let(::typeOf) ?: "JsonElement"}>"
            "object" -> {
                val additional = schema["additionalProperties"]
                if (additional is JsonObject) "Map<String, ${typeOf(additional)}>" else "JsonObject"
            }
            else -> "JsonElement"
        }
        return type.nullableIf(nullable)
    }

    private fun discriminatedUnion(schema: JsonObject): DiscriminatedUnion? {
        val raw = (schema["oneOf"] ?: schema["anyOf"]) as? JsonArray ?: return null
        val variants = raw.mapNotNull { it as? JsonObject }
        if (variants.size != raw.size || variants.any { it.types().firstOrNull() != "object" }) return null
        val candidates = variants.map { it["properties"]?.jsonObject?.keys.orEmpty() }
            .reduceOrNull(Set<String>::intersect).orEmpty().sorted()
        val property = candidates.firstOrNull { candidate ->
            val values = variants.mapNotNull {
                (it["properties"]?.jsonObject?.get(candidate) as? JsonObject)?.constantValue()
            }
            values.size == variants.size && values.distinct().size == values.size
        } ?: return null
        return DiscriminatedUnion(
            property,
            variants.map { variant ->
                val value = checkNotNull(
                    (variant["properties"]?.jsonObject?.get(property) as? JsonObject)?.constantValue(),
                )
                val title = (variant["title"]?.jsonPrimitive?.content ?: value)
                    .replace(Regex("v[0-9]+::.*$"), "")
                UnionVariant(name = schemaNamePrefix(schema, title), value = value, schema = variant)
            },
        )
    }

    private fun schemaNamePrefix(schema: JsonObject, title: String): String {
        val owner = definitions.entries.firstOrNull { it.value === schema }?.key.orEmpty()
        return owner + title.kotlinTypeName()
    }

    private data class Field(
        val wireName: String,
        val identifier: String,
        val type: String,
        val forcedValue: String?,
        val optional: Boolean,
    )

    private data class DiscriminatedUnion(val property: String, val variants: List<UnionVariant>)
    private data class UnionVariant(val name: String, val value: String, val schema: JsonObject)
    private enum class ModelKind { OBJECT, ENUM, UNION, STRING, LONG, DOUBLE, BOOLEAN, JSON }
}
