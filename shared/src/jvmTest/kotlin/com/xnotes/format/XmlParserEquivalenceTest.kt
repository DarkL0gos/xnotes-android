package com.xnotes.format

import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XmlParser replaced javax.xml for flow.xml and imported SVG. On every document here both must
 * build the same tree (names, namespaces, attributes, text, CDATA), and reject the same broken ones.
 */
class XmlParserEquivalenceTest {

    private fun dom(bytes: ByteArray): Element =
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            .newDocumentBuilder()
            .parse(bytes.inputStream())
            .documentElement

    /** A canonical dump: comments dropped, adjacent plain text merged, attributes sorted. */
    private fun dumpDom(el: Element): String = buildString {
        fun walk(e: Element, indent: String) {
            append(indent).append("<{").append(e.namespaceURI ?: "").append("}").append(e.localName).append(" q=").append(e.nodeName)
            val attrs = (0 until e.attributes.length).map { e.attributes.item(it) }
                .map { "{${it.namespaceURI ?: ""}}${it.localName}(${it.nodeName})=${it.nodeValue}" }.sorted()
            append(" ").append(attrs).append('\n')
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) append(indent).append("  text:").append(text.toString().replace("\n", "\\n")).append('\n')
                text.clear()
            }
            var c = e.firstChild
            while (c != null) {
                when (c.nodeType) {
                    Node.TEXT_NODE -> text.append(c.nodeValue)
                    Node.CDATA_SECTION_NODE -> { flush(); append(indent).append("  cdata:").append(c.nodeValue.replace("\n", "\\n")).append('\n') }
                    Node.ELEMENT_NODE -> { flush(); walk(c as Element, "$indent  ") }
                }
                c = c.nextSibling
            }
            flush()
        }
        walk(el, "")
    }

    private fun dumpOurs(el: XmlElement): String = buildString {
        fun walk(e: XmlElement, indent: String) {
            append(indent).append("<{").append(e.namespaceUri ?: "").append("}").append(e.localName).append(" q=").append(e.qualifiedName)
            val attrs = e.attributes.map { "{${it.namespaceUri ?: ""}}${it.localName}(${it.qualifiedName})=${it.value}" }.sorted()
            append(" ").append(attrs).append('\n')
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) append(indent).append("  text:").append(text.toString().replace("\n", "\\n")).append('\n')
                text.clear()
            }
            for (c in e.children) when (c) {
                is XmlText -> if (c.cdata) { flush(); append(indent).append("  cdata:").append(c.text.replace("\n", "\\n")).append('\n') } else text.append(c.text)
                is XmlElement -> { flush(); walk(c, "$indent  ") }
            }
            flush()
        }
        walk(el, "")
    }

    private fun assertSameTree(name: String, bytes: ByteArray) {
        val expected = dumpDom(dom(bytes))
        val actual = dumpOurs(XmlParser.parse(bytes))
        assertEquals(name, expected, actual)
    }

    private val corpus: Map<String, String> = mapOf(
        "plain svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect x="1" y="2" width="3" height="4"/></svg>""",
        "illustrator entities" to """<?xml version="1.0" encoding="utf-8"?>
<!-- Generator: Adobe Illustrator -->
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" [
	<!ENTITY ns_extend "http://ns.adobe.com/Extensibility/1.0/">
	<!ENTITY ns_svg "http://www.w3.org/2000/svg">
	<!ENTITY ns_xlink "http://www.w3.org/1999/xlink">
	<!ENTITY st0 "fill:#FF0000;stroke:#000000;">
]>
<svg version="1.1" xmlns:x="&ns_extend;" xmlns="&ns_svg;" xmlns:xlink="&ns_xlink;" x="0px" y="0px" viewBox="0 0 100 100" style="enable-background:new 0 0 100 100;" xml:space="preserve">
<style type="text/css">
	.st0{&st0;}
</style>
<g><path class="st0" d="M10,10 L90,10&#10;L90,90 z"/><use xlink:href="#p"/></g>
</svg>""",
        "inkscape namespaces" to """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:svg="http://www.w3.org/2000/svg" xmlns="http://www.w3.org/2000/svg"
   xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"
   width="210mm" height="297mm" inkscape:version="1.0" sodipodi:docname="drawing.svg">
  <sodipodi:namedview id="base" inkscape:zoom="0.35"/>
  <svg:g inkscape:label="Layer 1" inkscape:groupmode="layer"><svg:circle cx="5" cy="5" r="2"/></svg:g>
</svg>""",
        "text, cdata, refs, whitespace" to "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\"\ty=\"2\">A &amp; B &lt;c&gt; &#x1F600; &#233;<tspan>inner</tspan> tail<![CDATA[ <raw> & stuff ]]>end</text>" +
            "<style><![CDATA[ .a { fill: red } ]]></style><desc>line1\r\nline2\rline3</desc>" +
            "<rect title=\"multi\nline\ttabbed &#10;kept &#9;tab\" id='single \"quoted\"'/></svg>",
        "comments and pis" to "<?xml version='1.0'?><!--c--><svg xmlns='http://www.w3.org/2000/svg'><!-- inside --><?pi data?><g>a<!--x-->b</g></svg><!--after-->",
        "nested default namespaces" to "<a xmlns='urn:one'><b xmlns='urn:two'><c/></b><d xmlns=''><e/></d></a>",
        "prefixed attributes" to "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'><image xlink:href='data:,x' href='b'/><x xml:lang='ru'/></svg>",
        "flow xml" to FlowXml.write(TextFlow().apply { }).decodeToString(),
    )

    @Test fun buildsTheSameTreesAsJavax() {
        for ((name, xml) in corpus) assertSameTree(name, xml.toByteArray())
    }

    @Test fun decodesBomsAndDeclaredEncodingsLikeJavax() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>Привет é</text></svg>"
        assertSameTree("utf-8 bom", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + svg.toByteArray())
        assertSameTree("utf-16le bom", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + svg.toByteArray(Charsets.UTF_16LE))
        assertSameTree("utf-16be bom", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + svg.toByteArray(Charsets.UTF_16BE))
        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><svg xmlns=\"http://www.w3.org/2000/svg\"><text>café</text></svg>"
        assertSameTree("latin-1", latin.toByteArray(Charsets.ISO_8859_1))
    }

    @Test fun rejectsWhatJavaxRejects() {
        val broken = listOf(
            "<svg><rect", "", "<svg></g>", "<svg a='1' a='2'/>", "<svg>&undefined;</svg>", "<p:svg/>",
            "<svg>text</svg>trailing", "<svg a=1/>", "<svg><![CDATA[ open </svg>", "<svg>&#0;</svg>",
        )
        for (xml in broken) {
            val domFails = runCatching { dom(xml.toByteArray()) }.isFailure
            val oursFails = runCatching { XmlParser.parse(xml.toByteArray()) }.isFailure
            assertTrue("javax should reject: $xml", domFails)
            if (!oursFails) fail("XmlParser accepted: $xml")
        }
    }

    @Test fun boundsEntityExpansion() {
        val laughs = buildString {
            append("<!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\">")
            for (i in 1..9) append("<!ENTITY a$i \"${"&a${if (i == 1) "" else i - 1};".repeat(10)}\">")
            append("]><x>&a9;</x>")
        }
        assertTrue(runCatching { XmlParser.parse(laughs.toByteArray()) }.isFailure)
    }
}
