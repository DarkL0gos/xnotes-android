package com.xnotes.format

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.random.Random

/** The UTF-8 char IO under JsonPull/JsonWrite must match the java.io reader/writer it replaced. */
class Utf8CharsTest {
    private val random = Random(7)

    /** Mixed ASCII, Cyrillic, CJK, emoji (surrogate pairs) and control characters. */
    private fun sample(length: Int): String = buildString {
        while (this.length < length) {
            when (random.nextInt(6)) {
                0 -> append('a' + random.nextInt(26))
                1 -> append('А' + random.nextInt(32))
                2 -> append('一' + random.nextInt(500))
                3 -> appendCodePoint(0x1F600 + random.nextInt(60))
                4 -> append(random.nextInt(32).toChar())
                else -> append(' ')
            }
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) = append(String(Character.toChars(cp)))

    private fun jdkBytes(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> OutputStreamWriter(out, Charsets.UTF_8).use { it.write(text) } }.toByteArray()

    private fun ourBytes(text: String, writeInPieces: Boolean): ByteArray {
        val buffer = Buffer()
        val sink = Utf8CharSink(buffer)
        if (writeInPieces) {
            var i = 0
            while (i < text.length) {
                when (random.nextInt(3)) {
                    0 -> { sink.write(text[i].code); i++ }
                    1 -> { val n = minOf(text.length - i, random.nextInt(1, 40)); sink.write(text.substring(i, i + n)); i += n }
                    else -> { val n = minOf(text.length - i, random.nextInt(1, 40)); sink.write(text.toCharArray(), i, n); i += n }
                }
            }
        } else {
            sink.write(text)
        }
        sink.flush()
        return buffer.readByteArray()
    }

    private fun readAll(source: CharSource, chunk: Int = 16 * 1024): String {
        val sb = StringBuilder()
        val into = CharArray(chunk)
        while (true) {
            val n = source.read(into)
            if (n < 0) break
            sb.appendRange(into, 0, n)
        }
        return sb.toString()
    }

    @Test fun encodingMatchesOutputStreamWriter() {
        for (length in listOf(0, 1, 100, 16 * 1024 - 1, 16 * 1024, 16 * 1024 + 1, 100_000)) {
            val text = sample(length)
            assertArrayEquals("whole, $length", jdkBytes(text), ourBytes(text, writeInPieces = false))
            assertArrayEquals("pieces, $length", jdkBytes(text), ourBytes(text, writeInPieces = true))
        }
    }

    @Test fun aSurrogatePairSplitAtTheChunkEdgeStaysOneCodePoint() {
        val text = "x".repeat(16 * 1024 - 1) + "😀" + "y"
        assertArrayEquals(jdkBytes(text), ourBytes(text, writeInPieces = false))
    }

    @Test fun loneSurrogatesBecomeQuestionMarksLikeTheJdk() {
        for (text in listOf("a\uD800b", "\uDC00", "end\uD83D", "\uD83D😀")) {
            assertArrayEquals(text, jdkBytes(text), ourBytes(text, writeInPieces = false))
        }
    }

    @Test fun decodingMatchesInputStreamReaderAcrossChunkEdges() {
        for (length in listOf(0, 1, 10_000, 40_000, 200_000)) {
            val text = sample(length)
            val bytes = jdkBytes(text)
            val expected = InputStreamReader(bytes.inputStream(), Charsets.UTF_8).readText()
            for (chunk in listOf(1, 7, 16 * 1024)) {
                assertEquals("$length/$chunk", expected, readAll(Utf8CharSource(Buffer().write(bytes)), chunk))
            }
        }
    }

    @Test fun malformedInputReadsAsReplacementCharacters() {
        val bytes = byteArrayOf(0x61, 0xC3.toByte(), 0x62, 0xE2.toByte(), 0x82.toByte())
        assertEquals("a�b�", readAll(Utf8CharSource(Buffer().write(bytes))))
    }
}
