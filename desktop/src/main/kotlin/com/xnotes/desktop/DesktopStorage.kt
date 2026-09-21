package com.xnotes.desktop

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
