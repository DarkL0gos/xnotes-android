package com.xnotes.desktop

import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.util.Svg
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import kotlin.math.ceil
import kotlin.math.max

internal object DesktopImageCodec : ImageCodec {
    private const val MAX_EDGE = 4096
    private val cache = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>): Boolean = size > 12
    }

    override fun probeFile(path: String): ImageSize? = runCatching {
        val file = File(path)
        if (Svg.isSvgFile(file)) return@runCatching probeSvg(file)
        ImageIO.createImageInputStream(file).use { stream ->
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

    fun decode(file: File, requestedW: Int, requestedH: Int): BufferedImage? {
        val width = requestedW.coerceIn(1, MAX_EDGE)
        val height = requestedH.coerceIn(1, MAX_EDGE)
        val key = "${file.absolutePath}:${file.lastModified()}:${width / 128}:${height / 128}"
        synchronized(cache) { cache[key]?.let { return it } }
        val image = runCatching {
            if (Svg.isSvgFile(file)) renderSvg(file, width, height) else readRaster(file, width, height)
        }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = image }
        return image
    }

    private fun readRaster(file: File, width: Int, height: Int): BufferedImage? =
        ImageIO.createImageInputStream(file).use { stream ->
            if (stream == null) return@use null
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = stream
                val sample = max(1, minOf(reader.getWidth(0) / width, reader.getHeight(0) / height))
                reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
            } finally { reader.dispose() }
        }

    private fun renderSvg(file: File, width: Int, height: Int): BufferedImage? {
        val out = ByteArrayOutputStream()
        val t = PNGTranscoder()
        t.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
        t.addTranscodingHint(PNGTranscoder.KEY_EXECUTE_ONLOAD, false)
        t.addTranscodingHint(PNGTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "")
        t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width.toFloat())
        t.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height.toFloat())
        t.transcode(TranscoderInput(file.toURI().toString()), TranscoderOutput(out))
        return ImageIO.read(out.toByteArray().inputStream())
    }

    private fun probeSvg(file: File): ImageSize? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        fun size(name: String): Double? = Regex("[+-]?(?:[0-9]*[.])?[0-9]+")
            .find(root.getAttribute(name))?.value?.toDoubleOrNull()
        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+"))
            .mapNotNull(String::toDoubleOrNull)
        val width = size("width") ?: viewBox.getOrNull(2) ?: 512.0
        val height = size("height") ?: viewBox.getOrNull(3) ?: 512.0
        if (width <= 0 || height <= 0) return null
        return ImageSize(ceil(width).toInt().coerceAtMost(MAX_EDGE), ceil(height).toInt().coerceAtMost(MAX_EDGE))
    }
}
