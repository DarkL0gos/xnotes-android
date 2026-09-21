package com.xnotes.desktop

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Small host-only preference store; document contents remain in their bundle files. */
internal class DesktopPreferences(private val paths: DesktopPaths = DesktopPaths()) {
    private val file = paths.config.resolve("desktop.properties")

    fun lastFile(): File? = runCatching {
        if (!file.isFile) return@runCatching null
        val values = Properties().apply { file.inputStream().use { load(it) } }
        values.getProperty("lastFile")?.let(::File)?.takeIf { it.isFile }
    }.getOrNull()

    fun remember(fileToRemember: File) {
        paths.config.mkdirs()
        val temp = Files.createTempFile(paths.config.toPath(), ".desktop-", ".tmp")
        try {
            Files.newOutputStream(temp).use { out ->
                Properties().apply { setProperty("lastFile", fileToRemember.absolutePath) }.store(out, "xnotes desktop")
            }
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
