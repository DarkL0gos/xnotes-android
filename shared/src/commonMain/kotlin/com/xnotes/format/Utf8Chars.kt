package com.xnotes.format

import com.xnotes.core.platform.InputStream
import com.xnotes.core.platform.OutputStream
import com.xnotes.core.platform.asSink
import com.xnotes.core.platform.asSource
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer

/** Where [JsonWrite] puts its characters (the subset of `java.io.Writer` it uses). */
internal interface CharSink {
    fun write(s: String)

    /** One UTF-16 code unit; a surrogate pair arrives as two calls. */
    fun write(c: Int)

    fun write(chars: CharArray, offset: Int, length: Int)

    /** Push everything written so far into the underlying byte sink (without closing it). */
    fun flush()
}

/** Where [JsonPull] reads its characters from (the subset of `java.io.Reader` it uses). */
internal interface CharSource {
    /** Fill [into] from the start with up to its size characters; -1 at the end of input. */
    fun read(into: CharArray): Int
}

/**
 * UTF-8 encoding into [sink], as `OutputStreamWriter(out, UTF_8)` did: characters are gathered and
 * encoded in chunks, a surrogate pair split across two writes stays one code point, and a lone
 * surrogate becomes '?'.
 */
internal class Utf8CharSink(private val sink: BufferedSink) : CharSink {
    private val chars = CharArray(CHUNK)
    private var n = 0

    override fun write(s: String) {
        for (c in s) put(c)
    }

    override fun write(c: Int) = put(c.toChar())

    override fun write(chars: CharArray, offset: Int, length: Int) {
        for (i in offset until offset + length) put(chars[i])
    }

    private fun put(c: Char) {
        if (n == chars.size) drain(keepTrailingHighSurrogate = true)
        chars[n++] = c
    }

    /** Encode the gathered characters; a high surrogate at the end waits for its partner. */
    private fun drain(keepTrailingHighSurrogate: Boolean) {
        if (n == 0) return
        val hold = keepTrailingHighSurrogate && chars[n - 1].isHighSurrogate()
        val end = if (hold) n - 1 else n
        sink.writeUtf8(chars.concatToString(0, end))
        if (hold) {
            chars[0] = chars[n - 1]
            n = 1
        } else {
            n = 0
        }
    }

    override fun flush() {
        drain(keepTrailingHighSurrogate = false)
        sink.flush()
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/**
 * UTF-8 decoding from [source], as `InputStreamReader(in, UTF_8)` did: decoded in chunks that never
 * end inside a multi-byte sequence, with malformed input read as U+FFFD.
 */
internal class Utf8CharSource(private val source: BufferedSource) : CharSource {
    private var pending = ""
    private var pendingPos = 0

    override fun read(into: CharArray): Int {
        if (pendingPos >= pending.length) {
            if (!source.request(1)) return -1
            source.request(CHUNK_BYTES)
            val buffered = source.buffer.size
            var n = minOf(buffered, CHUNK_BYTES)
            // Back off to the start of a sequence cut by the chunk edge; the rest comes next time.
            // At the true end of input an incomplete sequence is decoded as it is (to U+FFFD).
            if (n < buffered || source.request(n + 1)) n = completeSequences(n)
            pending = source.buffer.readUtf8(n)
            pendingPos = 0
        }
        val count = minOf(into.size, pending.length - pendingPos)
        pending.toCharArray(into, 0, pendingPos, pendingPos + count)
        pendingPos += count
        return count
    }

    /** The longest prefix of the first [n] buffered bytes that ends on a code point boundary. */
    private fun completeSequences(n: Long): Long {
        var lead = n - 1
        var back = 0
        while (lead >= 0 && back < 3 && (source.buffer[lead].toInt() and 0xC0) == 0x80) {
            lead--
            back++
        }
        if (lead < 0) return n
        val b = source.buffer[lead].toInt() and 0xFF
        val length = when {
            b < 0x80 -> 1
            b and 0xE0 == 0xC0 -> 2
            b and 0xF0 == 0xE0 -> 3
            b and 0xF8 == 0xF0 -> 4
            else -> 1 // not a lead byte: malformed either way, decode as is
        }
        val cut = if (lead + length > n) lead else n
        return if (cut > 0) cut else n
    }

    private companion object {
        const val CHUNK_BYTES = 32L * 1024
    }
}

/** A UTF-8 [CharSink] over [out], which stays open: the caller [CharSink.flush]es before moving on. */
internal fun utf8Writer(out: OutputStream): CharSink = Utf8CharSink(out.asSink().buffer())

/** A UTF-8 [CharSource] over [input], which stays open. */
internal fun utf8Reader(input: InputStream): CharSource = Utf8CharSource(input.asSource().buffer())
