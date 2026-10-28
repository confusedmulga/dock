package com.dock.scanner.office

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * A parsed XML element. Names are "prefix:local" with a fixed prefix per namespace (see
 * [PREFIXES]), whatever prefix the file itself used, so readers can match on "w:p" safely.
 */
internal class XNode(val name: String, val attrs: Map<String, String>) {
    /** Elements and text, in document order; text is kept as [TEXT] nodes so mixed content stays in order. */
    val children = ArrayList<XNode>(4)
    private var content: StringBuilder? = null

    val isText: Boolean get() = name == TEXT

    /** For a text node its text; for an element, the text directly inside it (not its children's). */
    val text: String
        get() {
            if (isText) return content?.toString().orEmpty()
            var single: XNode? = null
            var many: StringBuilder? = null
            for (c in children) {
                if (!c.isText) continue
                if (single == null) single = c
                else (many ?: StringBuilder(single.text).also { many = it }).append(c.content)
            }
            return many?.toString() ?: single?.text.orEmpty()
        }

    internal fun appendText(chars: CharArray, start: Int, length: Int) {
        val last = children.lastOrNull()
        val node = if (last != null && last.isText) last else XNode(TEXT, emptyMap()).also { children += it }
        (node.content ?: StringBuilder().also { node.content = it }).appendRange(chars, start, start + length)
    }

    fun attr(name: String): String? = attrs[name]

    fun child(name: String): XNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XNode> = children.filter { it.name == name }

    /** Follows a path of child names, e.g. path("a:graphic", "a:graphicData"). */
    fun path(vararg names: String): XNode? {
        var node: XNode = this
        for (n in names) node = node.child(n) ?: return null
        return node
    }

    /** The first element with [name] anywhere below this one, depth first. */
    fun find(name: String): XNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String, into: MutableList<XNode> = ArrayList()): List<XNode> {
        for (c in children) {
            if (c.name == name) into += c
            c.findAll(name, into)
        }
        return into
    }

    /** All text below this element, in document order. */
    fun allText(): String = buildString { collectText(this) }

    private fun collectText(sb: StringBuilder) {
        content?.let { sb.append(it) }
        children.forEach { it.collectText(sb) }
    }

    companion object {
        const val TEXT = "#text"
    }
}

internal object Xml {

    /** Fixed prefixes for the namespaces the readers use; anything else keeps its local name only. */
    private val PREFIXES = mapOf(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main" to "w",
        "http://purl.oclc.org/ooxml/wordprocessingml/main" to "w",
        "http://schemas.openxmlformats.org/drawingml/2006/main" to "a",
        "http://purl.oclc.org/ooxml/drawingml/main" to "a",
        "http://schemas.openxmlformats.org/presentationml/2006/main" to "p",
        "http://purl.oclc.org/ooxml/presentationml/main" to "p",
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships" to "r",
        "http://purl.oclc.org/ooxml/officeDocument/relationships" to "r",
        "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" to "wp",
        "http://schemas.openxmlformats.org/drawingml/2006/picture" to "pic",
        "http://schemas.openxmlformats.org/drawingml/2006/chart" to "c",
        "http://schemas.microsoft.com/office/word/2010/wordprocessingShape" to "wps",
        "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" to "wpg",
        "http://schemas.openxmlformats.org/markup-compatibility/2006" to "mc",
        "urn:schemas-microsoft-com:vml" to "v",
        "urn:schemas-microsoft-com:office:office" to "o",
        "urn:oasis:names:tc:opendocument:xmlns:office:1.0" to "office",
        "urn:oasis:names:tc:opendocument:xmlns:text:1.0" to "text",
        "urn:oasis:names:tc:opendocument:xmlns:table:1.0" to "table",
        "urn:oasis:names:tc:opendocument:xmlns:style:1.0" to "style",
        "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" to "fo",
        "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" to "draw",
        "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" to "svg",
        "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" to "presentation",
        "http://www.w3.org/1999/xlink" to "xlink",
    )

    fun name(uri: String?, localName: String, qName: String): String {
        val local = localName.ifEmpty { qName.substringAfter(':') }
        if (uri.isNullOrEmpty()) return local
        val prefix = PREFIXES[uri] ?: return local
        return "$prefix:$local"
    }

    fun newParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Documents come from anywhere: never fetch external entities or DTDs.
        listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        return factory.newSAXParser()
    }

    /** Parses a whole part into a tree. For very large parts (sheet data) use [stream]. */
    fun parse(input: InputStream): XNode {
        val root = XNode("#document", emptyMap())
        val stack = ArrayDeque<XNode>().apply { addLast(root) }
        stream(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
                val node = XNode(name(uri, localName, qName), attributes(attributes))
                stack.last().children += node
                stack.addLast(node)
            }

            override fun endElement(uri: String?, localName: String, qName: String) {
                stack.removeLast()
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                stack.last().appendText(ch, start, length)
            }
        })
        return root.children.firstOrNull { !it.isText } ?: root
    }

    fun stream(input: InputStream, handler: DefaultHandler) {
        newParser().parse(InputSource(input), handler)
    }

    fun attributes(a: Attributes): Map<String, String> {
        if (a.length == 0) return emptyMap()
        val map = HashMap<String, String>(a.length * 2)
        for (i in 0 until a.length) {
            map[name(a.getURI(i), a.getLocalName(i), a.getQName(i))] = a.getValue(i)
        }
        return map
    }
}
