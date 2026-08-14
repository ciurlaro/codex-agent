import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal fun readPrivacyManifestDeclarations(file: File): Map<String, List<String>> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    val root = builder.parse(file).documentElement
    check(root.tagName == "plist") { "Privacy manifest root must be plist: ${file.path}" }
    val dictionary = root.childElements().singleOrNull { it.tagName == "dict" }
        ?: error("Privacy manifest must contain one root dictionary: ${file.path}")
    val accessed = dictionary.dictionaryEntries()["NSPrivacyAccessedAPITypes"] ?: return emptyMap()
    check(accessed.tagName == "array") { "NSPrivacyAccessedAPITypes must be an array" }
    val declarations = sortedMapOf<String, List<String>>()
    accessed.childElements().forEach { entry ->
        check(entry.tagName == "dict") { "Privacy accessed API entry must be a dictionary" }
        val values = entry.dictionaryEntries()
        val category = values.getValue("NSPrivacyAccessedAPIType").requiredString()
        val reasonsElement = values.getValue("NSPrivacyAccessedAPITypeReasons")
        check(reasonsElement.tagName == "array") { "Privacy accessed API reasons must be an array" }
        val reasons = reasonsElement.childElements().map(Element::requiredString)
        check(reasons.isNotEmpty() && reasons.size == reasons.distinct().size) {
            "Privacy accessed API reasons must be nonempty and unique: $category"
        }
        check(declarations.put(category, reasons.sorted()) == null) {
            "Duplicate privacy manifest declaration: $category"
        }
    }
    return declarations
}

private fun Element.dictionaryEntries(): Map<String, Element> {
    val children = childElements()
    check(children.size % 2 == 0) { "Malformed plist dictionary" }
    val entries = linkedMapOf<String, Element>()
    children.chunked(2).forEach { (key, value) ->
        check(key.tagName == "key") { "Malformed plist dictionary key" }
        check(entries.put(key.textContent, value) == null) { "Duplicate plist key: ${key.textContent}" }
    }
    return entries
}

private fun Element.requiredString(): String {
    check(tagName == "string" && textContent.isNotBlank()) { "Expected nonblank plist string" }
    return textContent
}

private fun Element.childElements(): List<Element> = buildList {
    val nodes = childNodes
    for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.let(::add)
}
