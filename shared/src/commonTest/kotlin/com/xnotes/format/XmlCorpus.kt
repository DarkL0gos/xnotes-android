package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.platform.javaDoubleToString
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import com.xnotes.core.vector.GradientStop
import com.xnotes.core.vector.VectorPaint
import com.xnotes.core.vector.VectorScene
import com.xnotes.core.vector.VectorSeg

/**
 * Inputs and platform-neutral summaries for checking that flow.xml and SVG import give the same
 * results on every platform: the JVM test pins the digests, the native test must reproduce them.
 */
object XmlCorpus {

    fun richFlow(): TextFlow = TextFlow().apply {
        margins = FlowMargins(15.0, 12.5, 15.0, 10.0)
        defaultFace = FontFace.SERIF
        defaultSizePt = 14.5
        defaultColor = Rgba(10, 20, 30, 255)
        monoFace = FontFace("JetBrains Mono")
        paragraphs.add(
            Paragraph(
                mutableListOf(
                    Run("plain <&> \"quotes\" 'apos' "),
                    Run("bold red", CharStyle(bold = true, color = Rgba(255, 0, 0, 255))),
                    Run(" Привет 😀", CharStyle(italic = true, underline = true, strike = true, sizePt = 18.25)),
                    Run(" named", CharStyle(face = FontFace("Playfair Display"))),
                ),
                align = ParaAlign.JUSTIFY,
            ),
        )
        paragraphs.add(Paragraph(mutableListOf(Run("todo  item   spaced")), indent = 2, list = ListKind.CHECK, checked = true))
        paragraphs.add(Paragraph(mutableListOf(Run("numbered")), list = ListKind.ORDERED))
        paragraphs.add(Paragraph())
        paragraphs.add(
            Paragraph(
                mutableListOf(Run("    if (x)\treturn a < b && c", CharStyle(code = true, highlight = Rgba(40, 40, 40, 255)))),
                codeLang = "kotlin",
            ),
        )
    }

    fun flowSummary(flow: TextFlow): String = buildString {
        append(flow.margins).append('|').append(flow.defaultFace).append('|').append(flow.monoFace).append('|')
        append(javaDoubleToString(flow.defaultSizePt)).append('|').append(flow.defaultColor).append('\n')
        for (p in flow.paragraphs) {
            append(p.align).append(' ').append(p.indent).append(' ').append(p.list).append(' ').append(p.checked).append(' ').append(p.codeLang)
            for (r in p.runs) append(" [").append(r.text).append("|").append(r.style.copy(sizePt = null)).append("|")
                .append(r.style.sizePt?.let { javaDoubleToString(it) }).append("]")
            append('\n')
        }
    }

    val svg: String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" [
	<!ENTITY ns_svg "http://www.w3.org/2000/svg">
	<!ENTITY ns_xlink "http://www.w3.org/1999/xlink">
]>
<svg version="1.1" xmlns="&ns_svg;" xmlns:xlink="&ns_xlink;" width="200" height="120" viewBox="0 0 400 240">
<style type="text/css"><![CDATA[
	.st0{fill:#3A7BD5;stroke:#102030;stroke-width:3;}
	.st1{fill:url(#grad);opacity:0.8}
]]></style>
<defs>
  <linearGradient id="grad" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="rgb(0,128,255)" stop-opacity="0.5"/></linearGradient>
  <path id="p" d="M10,10 C 40,0 60,40 90,10 A 20 30 15 0 1 120,60 Z"/>
</defs>
<g transform="translate(20,10) rotate(15) scale(1.5)">
  <rect class="st0" x="5" y="5" width="60" height="40" rx="6"/>
  <circle class="st1" cx="120" cy="60" r="25"/>
  <use xlink:href="#p" transform="translate(0,80)" fill="none" stroke="black" stroke-dasharray="4 2" stroke-linecap="round"/>
  <polyline points="0,0 10,20 30,5 50,40" fill="none" stroke="#0a0" stroke-linejoin="bevel"/>
  <ellipse cx="200" cy="100" rx="30" ry="12" fill="#ccc" fill-rule="evenodd"/>
</g>
<text x="10" y="220" font-size="24" fill="#222">Hi &amp; bye<tspan fill="red"> red</tspan></text>
</svg>"""

    private val NUMBER = Regex("""-?\d+(\.\d+)?(E-?\d+)?""")

    /** [sceneSummary] with every number replaced by `#`: what must match exactly. */
    fun sceneShape(summary: String): String = NUMBER.replace(summary, "#")

    /**
     * The numbers of [sceneSummary], in order. They are compared with a tolerance: arcs go through
     * sin/cos/atan2, which the JVM and Kotlin/Native (glibc libm) round differently in the last bits.
     * Scenes are rebuilt from the SVG's bytes at draw time and never saved, so that is all they owe.
     */
    fun sceneNumbers(summary: String): List<Double> = NUMBER.findAll(summary).map { it.value.toDouble() }.toList()

    fun sceneSummary(scene: VectorScene): String = buildString {
        fun d(v: Double) = javaDoubleToString(v)
        fun pt(p: Pt) = "${d(p.x)},${d(p.y)}"
        fun stops(list: List<GradientStop>) = list.joinToString(";") { "${d(it.offset)}@${it.color}" }
        fun paint(p: VectorPaint?): String = when (p) {
            null -> "none"
            is VectorPaint.Solid -> "solid${p.color}"
            is VectorPaint.Linear -> "linear(${d(p.x0)},${d(p.y0)}->${d(p.x1)},${d(p.y1)} ${p.spread} ${stops(p.stops)})"
            is VectorPaint.Radial -> "radial(${d(p.cx)},${d(p.cy)} r${d(p.r)} f${d(p.fx)},${d(p.fy)} ${p.spread} ${stops(p.stops)})"
        }
        append(d(scene.width)).append('x').append(d(scene.height)).append(" skipped=").append(scene.skipped.sorted()).append('\n')
        for (path in scene.paths) {
            append("fill=").append(paint(path.fill)).append(" rule=").append(path.fillRule)
            append(" stroke=").append(paint(path.stroke)).append(" w=").append(d(path.strokeWidth))
            append(" cap=").append(path.cap).append(" join=").append(path.join)
            append(" dash=").append(path.dash?.joinToString(",") { d(it) }).append('\n')
            for (c in path.contours) {
                append("  M").append(pt(c.start))
                for (s in c.segments) when (s) {
                    is VectorSeg.Line -> append(" L").append(pt(s.end))
                    is VectorSeg.Cubic -> append(" C").append(pt(s.c1)).append(' ').append(pt(s.c2)).append(' ').append(pt(s.end))
                    else -> append(" ?").append(pt(s.end))
                }
                if (c.closed) append(" Z")
                append('\n')
            }
        }
    }
}
