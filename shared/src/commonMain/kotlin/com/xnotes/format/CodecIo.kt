package com.xnotes.format

import com.xnotes.core.platform.File
import com.xnotes.core.platform.InputStream
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.asSource
import com.xnotes.core.platform.monotonicNanos
import okio.Buffer
import okio.FileSystem
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

/** Copy the rest of [input] (to its end, e.g. the end of a zip entry) into [file]; [input] stays open. */
internal fun copyToFile(input: InputStream, file: File) {
    FileSystem.SYSTEM.write(file.asPath()) { writeAll(input.asSource()) }
}

/** The rest of [input] as bytes; [input] stays open. */
internal fun readRemaining(input: InputStream): ByteArray = input.asSource().buffer().readByteArray()

/** [file]'s size, 0 when it is missing (as `File.length()`). */
internal fun lengthOf(file: File): Long = runCatching { FileSystem.SYSTEM.metadata(file.asPath()).size }.getOrNull() ?: 0L

/** Delete [file] if present, never throwing (as `File.delete()`). */
internal fun deleteQuietly(file: File) {
    runCatching { FileSystem.SYSTEM.delete(file.asPath()) }
}

/** `"%03d"`: at least three digits, zero-padded. */
internal fun pad3(n: Int): String = n.toString().padStart(3, '0')

/** Times what reads spend in [source] (the inflater, for the manifest). Never closes it: the zip outlives it. */
internal class InflateProbe(private val source: Source) : Source {
    var nanos = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val t = monotonicNanos()
        val n = source.read(sink, byteCount)
        nanos += monotonicNanos() - t
        return n
    }

    override fun timeout(): Timeout = source.timeout()

    override fun close() = Unit
}

/** Times and counts what is written to [sink] (the deflater, for the manifest). Never closes it. */
internal class DeflateProbe(private val sink: Sink) : Sink {
    var nanos = 0L
    var bytes = 0L

    override fun write(source: Buffer, byteCount: Long) {
        val t = monotonicNanos()
        sink.write(source, byteCount)
        nanos += monotonicNanos() - t
        bytes += byteCount
    }

    override fun flush() = sink.flush()

    override fun timeout(): Timeout = sink.timeout()

    override fun close() = Unit
}
