package com.xnotes.format

import com.xnotes.core.platform.CRC32
import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.pathString
import com.xnotes.core.platform.withLock
import okio.FileSystem
import okio.buffer
import okio.use

/**
 * Remembered CRC32s of the files that go into a bundle as STORED entries: images and the embedded
 * source PDF.
 *
 * A STORED entry has to carry its size and checksum in the header that precedes it, so the writer
 * used to read every asset twice per save, once to checksum and once to copy. On a note built from
 * a 500-page PDF that is the whole PDF read twice for a save that changed one stroke. A file that
 * has not changed cannot have a new checksum, so the second read is pure waste.
 *
 * Keyed on path, length and last-modified together, so a file replaced in place is still noticed.
 * Bounded, because a session can touch a lot of images and this must never become a leak.
 */
internal object AssetCrc {

    private const val MAX_ENTRIES = 64

    private val lock = Lock()

    /** Least recently used first: a hit moves its key to the end, and the front is evicted. */
    private val cache = LinkedHashMap<String, Long>(16, 0.75f)

    fun of(file: File): Long {
        val path = file.asPath()
        val meta = runCatching { FileSystem.SYSTEM.metadata(path) }.getOrNull()
        // Missing reads as length 0 and time 0, as File.length()/lastModified() did.
        val key = "${file.pathString}|${meta?.size ?: 0L}|${meta?.lastModifiedAtMillis ?: 0L}"
        withLock(lock) { cache.remove(key)?.also { cache[key] = it } }?.let { return it }
        val crc = compute(file)
        withLock(lock) {
            cache[key] = crc
            if (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        return crc
    }

    private fun compute(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileSystem.SYSTEM.source(file.asPath()).buffer().use { input ->
            while (true) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.getValue()
    }
}
