package com.xnotes.desktop

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.max

/** Per-user desktop paths. Source assets stay in the cache while their document is open. */
internal class DesktopPaths(private val overrideRoot: File? = null) {
    private val home = System.getProperty("user.home")
    val config = overrideRoot?.resolve("config") ?: xdg("XDG_CONFIG_HOME", ".config").resolve("xnotes")
    val cache = overrideRoot?.resolve("cache") ?: xdg("XDG_CACHE_HOME", ".cache").resolve("xnotes")

    private fun xdg(variable: String, fallback: String): File {
        val path = System.getenv(variable)?.takeIf { it.isNotBlank() }
        return if (path != null && File(path).isAbsolute) File(path) else File(home, fallback)
    }
}

internal sealed interface OpenDocument {
    val file: File
    data class Note(override val file: File, val value: Document) : OpenDocument
    data class Canvas(override val file: File, val value: InfiniteDocument) : OpenDocument
}

/** Reads assets into a private working directory and writes bundles without touching source on failure. */
internal class DesktopStorage(private val paths: DesktopPaths = DesktopPaths()) : AutoCloseable {
    private val noteCodec = DocumentCodec(DesktopImageCodec, DesktopTextMeasurer)
    private val canvasCodec = CanvasCodec(DesktopImageCodec)
    private var assets: File? = null

    fun open(file: File): OpenDocument {
        require(file.isFile) { "Файл не найден: $file" }
        val work = Files.createTempDirectory(paths.cache.apply { mkdirs() }.toPath(), "open-").toFile()
        try {
            val doc = when (file.extension.lowercase()) {
                "xnote" -> file.inputStream().buffered().use {
                    OpenDocument.Note(file, noteCodec.read(it, work, work).apply {
                        path = file.absolutePath
                        displayName = file.name
                    })
                }
                "xcanvas" -> file.inputStream().buffered().use {
                    OpenDocument.Canvas(file, canvasCodec.read(it, work).apply {
                        path = file.absolutePath
                        displayName = file.name
                    })
                }
                else -> error("Поддерживаются только .xnote и .xcanvas")
            }
            clearAssets()
            assets = work
            return doc
        } catch (t: Throwable) {
            work.deleteRecursively()
            throw t
        }
    }

    fun save(doc: OpenDocument, target: File) {
        val expected = when (doc) {
            is OpenDocument.Note -> "xnote"
            is OpenDocument.Canvas -> "xcanvas"
        }
        require(target.extension.lowercase() == expected) { "Требуется расширение .$expected" }
        val absolute = target.absoluteFile
        val parent = absolute.parentFile ?: error("Нет каталога назначения")
        require(parent.isDirectory) { "Каталог не найден: $parent" }
        val tmp = Files.createTempFile(parent.toPath(), ".xnotes-", ".tmp")
        try {
            Files.newOutputStream(tmp).buffered().use { out ->
                when (doc) {
                    is OpenDocument.Note -> noteCodec.write(doc.value, out)
                    is OpenDocument.Canvas -> canvasCodec.write(doc.value, out)
                }
            }
            try {
                Files.move(tmp, absolute.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, absolute.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            when (doc) {
                is OpenDocument.Note -> { doc.value.path = absolute.path; doc.value.displayName = absolute.name; doc.value.dirty = false }
                is OpenDocument.Canvas -> { doc.value.path = absolute.path; doc.value.displayName = absolute.name; doc.value.dirty = false }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun clearAssets() {
        assets?.deleteRecursively()
        assets = null
    }

    override fun close() = clearAssets()
}

private object DesktopImageCodec : ImageCodec {
    override fun probeFile(path: String): ImageSize? = runCatching {
        ImageIO.createImageInputStream(File(path)).use { stream ->
            if (stream == null) return@runCatching null
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = stream
                ImageSize(reader.getWidth(0), reader.getHeight(0))
            } finally { reader.dispose() }
        }
    }.getOrNull()
}

/** AWT metrics let the shared codec restore text bounds without Android graphics. */
private object DesktopTextMeasurer : TextMeasurer {
    private val context = FontRenderContext(null, true, true)

    private fun font(spec: FontSpec): Font {
        val family = when (spec.face) {
            FontFace.SERIF -> Font.SERIF
            FontFace.MONO -> Font.MONOSPACED
            FontFace.HAND -> Font.SANS_SERIF
            else -> if (spec.face == FontFace.SANS) Font.SANS_SERIF else spec.face.id
        }
        val style = (if (spec.bold) Font.BOLD else Font.PLAIN) or (if (spec.italic) Font.ITALIC else Font.PLAIN)
        return Font(family, style, 1).deriveFont(spec.pointSize.toFloat())
    }

    override fun metrics(font: FontSpec): LineMetrics {
        val m = font(font).getLineMetrics("Ag", context)
        return LineMetrics(m.ascent.toDouble(), m.descent.toDouble() + m.leading)
    }

    override fun lineHeight(font: FontSpec): Double = metrics(font).height

    override fun advances(text: String, font: FontSpec): DoubleArray {
        val f = font(font)
        return DoubleArray(text.length) { i ->
            f.getStringBounds(text.substring(0, i + 1), context).width -
                f.getStringBounds(text.substring(0, i), context).width
        }
    }

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val width = max(1.0, wrapWidth)
        var lines = 1
        var used = 0.0
        for ((index, line) in text.split('\n').withIndex()) {
            if (index > 0) lines++
            for (advance in advances(line, font)) {
                if (flags.wordWrap && used > 0.0 && used + advance > width) {
                    lines++
                    used = 0.0
                }
                used += advance
            }
            used = 0.0
        }
        return Rect(0.0, 0.0, wrapWidth, lines * lineHeight(font))
    }
}
