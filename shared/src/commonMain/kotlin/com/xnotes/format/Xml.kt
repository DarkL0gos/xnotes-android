package com.xnotes.format

/*
 * A small non-validating XML parser and the read-only tree it builds, for flow.xml and imported SVG.
 * It follows what javax.xml's parser did for those files: namespaces, entity and character
 * references (internal DOCTYPE entities too, which Illustrator's SVGs rely on), CDATA, line-end
 * and attribute-value normalization, and failing on anything not well-formed. External DTDs and
 * external entities are never fetched, and entity expansion is bounded.
 */

/** Not well-formed XML. */
class XmlException(message: String) : Exception(message)

sealed class XmlNode

/** Character data; [cdata] when it came from a CDATA section. */
class XmlText(val text: String, val cdata: Boolean) : XmlNode()

class XmlAttribute(val qualifiedName: String, val localName: String, val namespaceUri: String?, val value: String)

class XmlElement(
    /** The name as written, prefix included (DOM's nodeName). */
    val qualifiedName: String,
    val localName: String,
    val namespaceUri: String?,
    val attributes: List<XmlAttribute>,
) : XmlNode() {
    val children: MutableList<XmlNode> = ArrayList()

    /** The attribute written as [qualifiedName], or "" (DOM's getAttribute). */
    fun getAttribute(qualifiedName: String): String =
        attributes.firstOrNull { it.qualifiedName == qualifiedName }?.value ?: ""

    /** The attribute [localName] in namespace [namespaceUri], or "" (DOM's getAttributeNS). */
    fun getAttributeNS(namespaceUri: String?, localName: String): String =
        attributes.firstOrNull { it.namespaceUri == namespaceUri && it.localName == localName }?.value ?: ""

    fun childElements(): List<XmlElement> = children.filterIsInstance<XmlElement>()

    /** All descendant character data, in document order (DOM's textContent). */
    val textContent: String
        get() = buildString { appendText(this@XmlElement) }

    private fun StringBuilder.appendText(el: XmlElement) {
        for (c in el.children) when (c) {
            is XmlText -> append(c.text)
            is XmlElement -> appendText(c)
        }
    }
}

object XmlParser {
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    /** Past this many characters of entity expansion the document is refused (billion-laughs guard). */
    private const val MAX_EXPANSION = 1_000_000

    /** The document element of [bytes]. */
    fun parse(bytes: ByteArray): XmlElement = Parser(normalizeNewlines(decode(bytes))).document()

    /** The text of [bytes], by BOM or the declared encoding (UTF-8, UTF-16 or ISO-8859-1). */
    private fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return bytes.decodeToString(3, bytes.size)
        if (bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) return utf16(bytes, 2, bigEndian = true)
        if (bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) return utf16(bytes, 2, bigEndian = false)
        if (bytes.size >= 4 && b(0) == 0 && b(1) == '<'.code) return utf16(bytes, 0, bigEndian = true)
        if (bytes.size >= 4 && b(0) == '<'.code && b(1) == 0 && b(3) == 0) return utf16(bytes, 0, bigEndian = false)
        // The declaration is ASCII in every encoding this handles, so read it as Latin-1 first.
        val head = buildString { for (i in 0 until minOf(bytes.size, 200)) append(b(i).toChar()) }
        val declared = Regex("""^<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(head)?.groupValues?.get(1)?.uppercase()
        return when (declared) {
            "ISO-8859-1", "LATIN1", "ISO8859-1", "US-ASCII", "ASCII" ->
                buildString(bytes.size) { for (x in bytes) append((x.toInt() and 0xFF).toChar()) }
            else -> bytes.decodeToString()
        }
    }

    private fun utf16(bytes: ByteArray, from: Int, bigEndian: Boolean): String = buildString((bytes.size - from) / 2) {
        var i = from
        while (i + 1 < bytes.size) {
            val hi = bytes[i].toInt() and 0xFF
            val lo = bytes[i + 1].toInt() and 0xFF
            append((if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar())
            i += 2
        }
    }

    /** XML 1.0 §2.11: CR LF and lone CR both become LF. */
    private fun normalizeNewlines(s: String): String =
        if ('\r' !in s) s else s.replace("\r\n", "\n").replace('\r', '\n')

    private class Scope(val parent: Scope?, val bindings: Map<String, String>) {
        fun resolve(prefix: String): String? = bindings[prefix] ?: parent?.resolve(prefix)
    }

    private class Parser(private val s: String) {
        private var pos = 0
        private val entities = HashMap<String, String>()
        private var expanded = 0

        fun document(): XmlElement {
            skipProlog()
            if (!startsWith("<") || peek(1) == '/' ) fail("no document element")
            val root = element(Scope(null, mapOf("xml" to XML_NS)))
            // Trailing misc: comments, PIs, whitespace only.
            while (true) {
                skipWhitespace()
                when {
                    pos >= s.length -> return root
                    startsWith("<!--") -> comment()
                    startsWith("<?") -> processingInstruction()
                    else -> fail("content after the document element")
                }
            }
        }

        private fun skipProlog() {
            if (startsWith("<?xml") && (peek(5).isWhitespace())) processingInstruction()
            while (true) {
                skipWhitespace()
                when {
                    startsWith("<!--") -> comment()
                    startsWith("<?") -> processingInstruction()
                    startsWith("<!DOCTYPE") -> doctype()
                    else -> return
                }
            }
        }

        /** Skips the DOCTYPE, keeping the internal subset's general entities (never fetching anything). */
        private fun doctype() {
            pos += "<!DOCTYPE".length
            while (pos < s.length) {
                when (s[pos]) {
                    '"', '\'' -> skipQuoted()
                    '[' -> {
                        pos++
                        internalSubset()
                    }
                    '>' -> {
                        pos++
                        return
                    }
                    else -> pos++
                }
            }
            fail("unterminated DOCTYPE")
        }

        private fun internalSubset() {
            while (pos < s.length) {
                skipWhitespace()
                when {
                    startsWith("]") -> {
                        pos++
                        return
                    }
                    startsWith("<!--") -> comment()
                    startsWith("<?") -> processingInstruction()
                    startsWith("<!ENTITY") -> entityDeclaration()
                    startsWith("<!") -> skipMarkupDeclaration()
                    startsWith("%") -> {
                        // A parameter-entity reference: its declarations are external or unknown; skip.
                        while (pos < s.length && s[pos] != ';') pos++
                        pos++
                    }
                    else -> fail("unexpected content in the DOCTYPE")
                }
            }
            fail("unterminated internal subset")
        }

        private fun entityDeclaration() {
            pos += "<!ENTITY".length
            skipWhitespace()
            val parameter = startsWith("%")
            if (parameter) {
                pos++
                skipWhitespace()
            }
            val name = name()
            skipWhitespace()
            if (startsWith("\"") || startsWith("'")) {
                val q = s[pos++]
                val start = pos
                while (pos < s.length && s[pos] != q) pos++
                if (pos >= s.length) fail("unterminated entity value")
                val raw = s.substring(start, pos)
                pos++
                // Character references in a literal are expanded at declaration; entity references at use.
                if (!parameter && name !in entities) entities[name] = expandCharRefs(raw)
            }
            // External (SYSTEM/PUBLIC) entities are never fetched: an unknown reference later fails.
            skipMarkupDeclaration()
        }

        private fun skipMarkupDeclaration() {
            while (pos < s.length && s[pos] != '>') {
                if (s[pos] == '"' || s[pos] == '\'') skipQuoted() else pos++
            }
            if (pos >= s.length) fail("unterminated declaration")
            pos++
        }

        private fun skipQuoted() {
            val q = s[pos++]
            while (pos < s.length && s[pos] != q) pos++
            if (pos >= s.length) fail("unterminated literal")
            pos++
        }

        private fun comment() {
            val end = s.indexOf("-->", pos + 4)
            if (end < 0) fail("unterminated comment")
            pos = end + 3
        }

        private fun processingInstruction() {
            val end = s.indexOf("?>", pos + 2)
            if (end < 0) fail("unterminated processing instruction")
            pos = end + 2
        }

        private fun element(parentScope: Scope): XmlElement {
            pos++ // '<'
            val qName = name()
            val raw = ArrayList<Pair<String, String>>()
            while (true) {
                val hadSpace = skipWhitespace()
                when {
                    startsWith("/>") || startsWith(">") -> break
                    !hadSpace -> fail("attributes must be separated by whitespace")
                }
                val an = name()
                skipWhitespace()
                expect('=')
                skipWhitespace()
                val value = attributeValue()
                if (raw.any { it.first == an }) fail("duplicate attribute $an")
                raw += an to value
            }
            val bindings = HashMap<String, String>()
            for ((n, v) in raw) {
                if (n == "xmlns") bindings[""] = v
                else if (n.startsWith("xmlns:")) bindings[n.substring(6)] = v
            }
            val scope = if (bindings.isEmpty()) parentScope else Scope(parentScope, bindings)
            val (prefix, local) = split(qName)
            val ns = if (prefix == null) scope.resolve("")?.takeIf { it.isNotEmpty() }
                else scope.resolve(prefix) ?: fail("undeclared prefix $prefix")
            val attributes = raw.map { (n, v) ->
                val (p, l) = split(n)
                val ans = when {
                    n == "xmlns" || p == "xmlns" -> XMLNS
                    p == null -> null
                    else -> scope.resolve(p) ?: fail("undeclared prefix $p")
                }
                XmlAttribute(n, l, ans, v)
            }
            val el = XmlElement(qName, local, ns, attributes)
            if (startsWith("/>")) {
                pos += 2
                return el
            }
            pos++ // '>'
            content(el, scope)
            // End tag
            pos += 2 // "</"
            val end = name()
            if (end != qName) fail("mismatched end tag </$end> for <$qName>")
            skipWhitespace()
            expect('>')
            return el
        }

        private fun content(el: XmlElement, scope: Scope) {
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    el.children += XmlText(text.toString(), cdata = false)
                    text.clear()
                }
            }
            while (true) {
                if (pos >= s.length) fail("unterminated element <${el.qualifiedName}>")
                val c = s[pos]
                when {
                    c == '<' && startsWith("</") -> {
                        flushText()
                        return
                    }
                    c == '<' && startsWith("<!--") -> comment()
                    c == '<' && startsWith("<![CDATA[") -> {
                        flushText()
                        val end = s.indexOf("]]>", pos + 9)
                        if (end < 0) fail("unterminated CDATA section")
                        el.children += XmlText(s.substring(pos + 9, end), cdata = true)
                        pos = end + 3
                    }
                    c == '<' && startsWith("<?") -> processingInstruction()
                    c == '<' -> {
                        flushText()
                        el.children += element(scope)
                    }
                    c == '&' -> text.append(reference(inAttribute = false))
                    else -> {
                        text.append(c)
                        pos++
                    }
                }
            }
        }

        private fun attributeValue(): String {
            if (pos >= s.length || (s[pos] != '"' && s[pos] != '\'')) fail("attribute value must be quoted")
            val q = s[pos++]
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated attribute value")
                val c = s[pos]
                when {
                    c == q -> {
                        pos++
                        return out.toString()
                    }
                    c == '<' -> fail("'<' in an attribute value")
                    c == '&' -> {
                        val charRef = startsWith("&#")
                        val r = reference(inAttribute = true)
                        // §3.3.3: literal whitespace becomes a space; a character reference stays as written.
                        out.append(if (charRef) r else normalizeAttributeWhitespace(r))
                    }
                    c == '\t' || c == '\n' -> {
                        out.append(' ')
                        pos++
                    }
                    else -> {
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        private fun normalizeAttributeWhitespace(v: String): String =
            v.replace('\t', ' ').replace('\n', ' ')

        /** A `&...;` reference at [pos], expanded. */
        private fun reference(inAttribute: Boolean): String {
            val end = s.indexOf(';', pos)
            if (end < 0 || end - pos > 64) fail("unterminated reference")
            val body = s.substring(pos + 1, end)
            pos = end + 1
            if (body.startsWith("#")) return charRef(body)
            return when (body) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "apos" -> "'"
                "quot" -> "\""
                else -> expandEntity(body, inAttribute, depth = 0)
            }
        }

        private fun expandEntity(name: String, inAttribute: Boolean, depth: Int): String {
            val value = entities[name] ?: fail("undefined entity &$name;")
            if (depth > 16) fail("entity nesting too deep")
            // The replacement text may reference further entities.
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '&') {
                    val end = value.indexOf(';', i)
                    if (end < 0) fail("bad reference in entity $name")
                    val body = value.substring(i + 1, end)
                    out.append(
                        when (body) {
                            "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "apos" -> "'"; "quot" -> "\""
                            else -> if (body.startsWith("#")) charRef(body) else expandEntity(body, inAttribute, depth + 1)
                        },
                    )
                    i = end + 1
                } else {
                    out.append(c)
                    i++
                }
            }
            expanded += out.length
            if (expanded > MAX_EXPANSION) fail("entity expansion limit exceeded")
            val text = out.toString()
            if (!inAttribute && '<' in text) fail("markup in entity &$name; is not supported")
            return if (inAttribute) normalizeAttributeWhitespace(text) else text
        }

        private fun charRef(body: String): String {
            val code = if (body.startsWith("#x")) body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
            if (code == null || code < 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) fail("bad character reference &$body;")
            if (code < 0x20 && code != 0x9 && code != 0xA && code != 0xD) fail("illegal character reference &$body;")
            return if (code < 0x10000) code.toChar().toString() else {
                val v = code - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }

        private fun expandCharRefs(raw: String): String {
            if ("&#" !in raw) return raw
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (raw.startsWith("&#", i)) {
                    val end = raw.indexOf(';', i)
                    if (end < 0) fail("bad character reference")
                    out.append(charRef(raw.substring(i + 1, end)))
                    i = end + 1
                } else {
                    out.append(raw[i++])
                }
            }
            return out.toString()
        }

        private fun name(): String {
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c.isWhitespace() || c == '=' || c == '>' || c == '/' || c == '?' || c == ';' || c == '"' || c == '\'' || c == '<' || c == '[') break
                pos++
            }
            if (pos == start) fail("expected a name")
            return s.substring(start, pos)
        }

        private fun split(qName: String): Pair<String?, String> {
            val i = qName.indexOf(':')
            return if (i < 0) null to qName else qName.substring(0, i) to qName.substring(i + 1)
        }

        private fun skipWhitespace(): Boolean {
            val start = pos
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\t' || s[pos] == '\r')) pos++
            return pos > start
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("expected '$c'")
            pos++
        }

        private fun startsWith(p: String) = s.startsWith(p, pos)
        private fun peek(ahead: Int): Char = if (pos + ahead < s.length) s[pos + ahead] else '\u0000'

        private fun fail(message: String): Nothing = throw XmlException("$message at offset $pos")
    }
}
