package com.xnotes.desktop

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextHitInfo
import java.awt.font.TextLayout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** The same concrete font is used for measurement and Java2D painting. */
internal object DesktopFonts {
    private val slugs = mapOf(
        "Fira Sans" to "fira-sans", "Inter" to "inter", "Lato" to "lato",
        "Montserrat" to "montserrat", "Nunito" to "nunito", "Open Sans" to "open-sans",
        "Poppins" to "poppins", "Raleway" to "raleway", "Roboto" to "roboto",
        "Source Sans 3" to "source-sans-3", "Ubuntu" to "ubuntu", "Lora" to "lora",
        "Playfair Display" to "playfair-display", "Fira Code" to "fira-code",
        "IBM Plex Mono" to "ibm-plex-mono", "JetBrains Mono" to "jetbrains-mono",
        "Roboto Mono" to "roboto-mono", "Source Code Pro" to "source-code-pro",
        "Ubuntu Mono" to "ubuntu-mono",
    )
    private val base = ConcurrentHashMap<String, Font>()

    fun resolve(spec: FontSpec): Font {
        val family = when (spec.face) {
            FontFace.SANS -> Font.SANS_SERIF
            FontFace.SERIF -> Font.SERIF
            FontFace.MONO -> Font.MONOSPACED
            FontFace.HAND -> Font.DIALOG
            else -> spec.face.id
        }
        val style = (if (spec.bold) Font.BOLD else Font.PLAIN) or (if (spec.italic) Font.ITALIC else Font.PLAIN)
        val slug = slugs[spec.face.id]
        if (slug == null) {
            val custom = File(DesktopPaths().config, "fonts/${spec.face.id}.ttf")
            if (custom.isFile) return loadCustom(custom)?.deriveFont(style, spec.pointSize.toFloat())
                ?: Font(family, style, 1).deriveFont(spec.pointSize.toFloat())
            return Font(family, style, 1).deriveFont(spec.pointSize.toFloat())
        }
        val name = when {
            spec.bold && spec.italic -> "bolditalic.ttf"
            spec.bold -> "bold.ttf"
            spec.italic -> "italic.ttf"
            else -> "regular.ttf"
        }
        val regular = base.getOrPut("$slug/regular.ttf") { loadBundled(slug, "regular.ttf") ?: Font(Font.SANS_SERIF, Font.PLAIN, 1) }
        val selected = base.getOrPut("$slug/$name") { loadBundled(slug, name) ?: regular }
        return selected.deriveFont(style, spec.pointSize.toFloat())
    }

    private fun loadBundled(slug: String, name: String): Font? = runCatching {
        javaClass.getResourceAsStream("/fonts/$slug/$name")?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }.getOrNull()

    private fun loadCustom(file: File): Font? = runCatching {
        base.getOrPut(file.absolutePath) { Font.createFont(Font.TRUETYPE_FONT, file) }
    }.getOrNull()
}

internal object DesktopTextMeasurer : TextMeasurer {
    val context = FontRenderContext(null, true, true)

    override fun metrics(font: FontSpec): LineMetrics {
        val m = DesktopFonts.resolve(font).getLineMetrics("Ag", context)
        return LineMetrics(m.ascent.toDouble(), m.descent.toDouble() + m.leading)
    }

    override fun lineHeight(font: FontSpec): Double = metrics(font).height

    override fun advances(text: String, font: FontSpec): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        val layout = TextLayout(text, DesktopFonts.resolve(font), context)
        val stops = DoubleArray(text.length + 1) { i ->
            if (i == text.length) layout.advance.toDouble()
            else layout.getCaretInfo(TextHitInfo.leading(i))[0].toDouble()
        }
        return DoubleArray(text.length) { i -> max(0.0, stops[i + 1] - stops[i]) }
    }

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        return Rect(0.0, 0.0, wrapWidth,
            DesktopTextLayout.lines(text, font, wrapWidth, flags.wordWrap).size * lineHeight(font))
    }
}

/** One line breaking rule shared by measurement and painting of text boxes. */
internal object DesktopTextLayout {
    fun lines(text: String, font: FontSpec, width: Double, wrap: Boolean): List<String> = buildList {
        for (paragraph in text.split('\n')) {
            if (!wrap || paragraph.isEmpty()) {
                add(paragraph)
                continue
            }
            val advances = DesktopTextMeasurer.advances(paragraph, font)
            var start = 0
            while (start < paragraph.length) {
                var end = start
                var used = 0.0
                var lastBreak = -1
                while (end < paragraph.length && (used + advances[end] <= max(1.0, width) || end == start)) {
                    used += advances[end]
                    if (paragraph[end].isWhitespace()) lastBreak = end + 1
                    end++
                }
                if (end < paragraph.length && lastBreak > start) end = lastBreak
                add(paragraph.substring(start, end))
                start = end
            }
        }
    }
}
