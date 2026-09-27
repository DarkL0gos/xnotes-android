package com.xnotes.core.util

import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.asFile
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.canonicalPathString
import com.xnotes.core.platform.withLock
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

// A shared generation prevents another pane or an old render from restoring discarded thumbnails.
class ThumbnailCacheFiles(dir: File, private val format: Int, private val maxFiles: Int = 256) {
    private class State {
        val lock = Lock()
        var theme: String? = null
        var generation = 0L
    }

    private val dir: Path = dir.asPath()
    private val fs = FileSystem.SYSTEM

    private val state = withLock(statesLock) { states.getOrPut(dir.canonicalPathString()) { State() } }

    fun useTheme(theme: String): Long = withLock(state.lock) {
        val signature = "$format\n$theme"
        if (state.theme != signature) {
            state.generation++
            state.theme = signature
            runCatching {
                val mark = dir / MARK
                if (!fs.exists(mark) || fs.read(mark) { readUtf8() } != signature) {
                    list().forEach { delete(it) }
                    fs.createDirectories(dir)
                    fs.write(mark) { writeUtf8(signature) }
                }
            }
        }
        state.generation
    }

    fun isCurrent(generation: Long): Boolean = withLock(state.lock) {
        state.theme != null && state.generation == generation
    }

    fun <T> load(uri: String, generation: Long, read: (File) -> T?): T? = withLock(state.lock) {
        if (!isCurrent(generation)) return@withLock null
        val file = dir / "${key(uri)}.png"
        if (!fs.exists(file)) return@withLock null
        runCatching { read(file.asFile()) }.getOrNull()
    }

    fun store(uri: String, generation: Long, write: (File) -> Unit) = withLock(state.lock) {
        if (!isCurrent(generation)) return@withLock
        runCatching {
            fs.createDirectories(dir)
            write((dir / "${key(uri)}.png").asFile())
            val pngs = list().filter { it.name.endsWith(".png") }
            pngs.sortedByDescending { lastModified(it) }.drop(maxFiles).forEach { removeFile(it) }
        }
        Unit
    }

    fun remove(uri: String) = withLock(state.lock) {
        removeFile(dir / "${key(uri)}.png")
    }

    fun prune(keep: Set<String>) = withLock(state.lock) {
        runCatching {
            val keys = keep.mapTo(HashSet()) { key(it) }
            list().forEach { file ->
                if (file.name != MARK && file.name.substringBeforeLast('.') !in keys) delete(file)
            }
        }
        Unit
    }

    /** The directory's entries, or none when it is missing (like `File.listFiles` returning null). */
    private fun list(): List<Path> = fs.listOrNull(dir).orEmpty()

    private fun lastModified(file: Path): Long =
        runCatching { fs.metadata(file).lastModifiedAtMillis }.getOrNull() ?: 0L

    /** Delete [file], never throwing, as `File.delete` never did. */
    private fun delete(file: Path) {
        runCatching { fs.delete(file) }
    }

    private fun removeFile(file: Path) {
        delete(file)
        delete(dir / "${file.name.substringBeforeLast('.')}.txt")
    }

    private fun key(uri: String): String = uri.encodeUtf8().sha256().hex().take(32)

    private companion object {
        const val MARK = "format"
        val statesLock = Lock()
        val states = HashMap<String, State>()
    }
}
