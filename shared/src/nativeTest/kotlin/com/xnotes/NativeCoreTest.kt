package com.xnotes

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.geometry.Pt
import com.xnotes.core.platform.CRC32
import com.xnotes.core.platform.IdCounter
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.ZipEntry
import com.xnotes.core.platform.ZipException
import com.xnotes.core.platform.ZipInputStream
import com.xnotes.core.platform.ZipMethod
import com.xnotes.core.platform.ZipOutputStream
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asOutputStream
import com.xnotes.core.platform.formatDecimal
import com.xnotes.core.platform.formatInstant
import com.xnotes.core.platform.identityMap
import com.xnotes.core.platform.monotonicNanos
import com.xnotes.core.platform.parseInstant
import com.xnotes.core.platform.roundHalfUp
import com.xnotes.core.platform.withLock
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The core's platform layer on Kotlin/Native, and the files it writes against what the JVM writes. */
class NativeCoreTest {

    private fun zip(block: (ZipOutputStream) -> Unit): ByteArray {
        val buffer = Buffer()
        ZipOutputStream(buffer.asOutputStream()).use(block)
        return buffer.readByteArray()
    }

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(Buffer().write(bytes).asInputStream()).use { zis ->
            while (true) {
                val e = zis.getNextEntry() ?: break
                val data = Buffer()
                val chunk = ByteArray(1000)
                while (true) {
                    val n = zis.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    data.write(chunk, 0, n)
                }
                out += e.getName() to data.readByteArray()
            }
        }
        return out
    }

    private fun crcOf(b: ByteArray) = CRC32().apply { update(b, 0, b.size) }.getValue()

    @Test fun crcMatchesKnownValues() {
        assertEquals(0L, crcOf(ByteArray(0)))
        assertEquals(0xCBF43926L, crcOf("123456789".encodeToByteArray())) // the standard check value
    }

    @Test fun zipRoundTripsStoredDeflatedEmptyAndLargeEntries() {
        val random = Random(3)
        val big = ByteArray(3_000_000) { (random.nextInt(8) + 'a'.code).toByte() }
        val noise = random.nextBytes(200_000)
        val entries = listOf(
            "manifest.json" to "{\"hello\":\"мир\"}".encodeToByteArray(),
            "empty" to ByteArray(0),
            "big.txt" to big,
            "noise.bin" to noise,
            "assets/image-000.png" to byteArrayOf(9, 8, 7),
        )
        val bytes = zip { zos ->
            zos.setLevel(1)
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (name.startsWith("assets/")) {
                    e.setMethod(ZipMethod.STORED)
                    e.setSize(data.size.toLong())
                    e.setCompressedSize(data.size.toLong())
                    e.setCrc(crcOf(data))
                } else {
                    e.setMethod(ZipMethod.DEFLATED)
                }
                zos.putNextEntry(e)
                // Uneven writes, as streams deliver them.
                var i = 0
                while (i < data.size) {
                    val n = minOf(data.size - i, 1 + random.nextInt(70_000))
                    zos.write(data, i, n)
                    i += n
                }
                zos.closeEntry()
            }
        }
        assertTrue(bytes.size < big.size, "deflate should compress the repetitive entry")
        val back = unzip(bytes)
        assertEquals(entries.map { it.first }, back.map { it.first })
        for ((a, b) in entries.zip(back)) assertContentEquals(a.second, b.second, a.first)
    }

    @Test fun aCorruptEntryFailsItsCrcCheck() {
        val data = "some text that compresses a little a little a little".encodeToByteArray()
        val bytes = zip { zos ->
            val e = ZipEntry("x").apply {
                setMethod(ZipMethod.STORED); setSize(data.size.toLong()); setCompressedSize(data.size.toLong()); setCrc(crcOf(data))
            }
            zos.putNextEntry(e)
            zos.write(data)
            zos.closeEntry()
        }
        bytes[40] = (bytes[40] + 1).toByte() // inside the stored data
        assertFailsWith<ZipException> { unzip(bytes) }
    }

    @Test fun aStoredEntryNeedsItsCrcUpFront() {
        assertFailsWith<ZipException> {
            zip { zos -> zos.putNextEntry(ZipEntry("x").apply { setMethod(ZipMethod.STORED); setSize(1) }) }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun readsANoteTheJvmWrote() {
        val bytes = Base64.decode(JVM_NOTE)
        val work = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "xnotes-native-test-${Random.nextLong().toULong()}")
        FileSystem.SYSTEM.createDirectories(work)
        try {
            val doc = DocumentCodec(FakeImageCodec(), FakeTextMeasurer()).read(Buffer().write(bytes).asInputStream(), work, work)
            assertEquals(2, doc.pages.size)
            val items = doc.pages[0].items
            assertEquals(6, items.size)
            val text = items.filterIsInstance<TextItem>().single()
            assertEquals("Привет, \"мир\"\\ / \t tab\r\n\u0001\u001f 😀 end", text.text)
            assertEquals(text.text, doc.bookmarks.single().label)
            assertEquals(40, (items[0] as Stroke).samples.size)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(work)
        }
    }

    @Test fun aNoteWrittenHereReadsBack() {
        val doc = Document.blank(2)
        doc.pages[0].items.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), MutableList(50) { Sample(it * 2.5, it * 1.5, 0.5) }))
        doc.pages[0].items.add(TextItem(Pt(10.0, 20.0), width = 100.0, text = "Текст 😀", rgba = Rgba(1, 2, 3, 255), pointSize = 12.0, measurer = FakeTextMeasurer()))
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val buffer = Buffer()
        codec.write(doc, buffer.asOutputStream())
        val bytes = buffer.readByteArray()
        // Left in build/ for checking with other ZIP tools and the JVM reader.
        val out = "build/native-written.xnote".toPath()
        runCatching { FileSystem.SYSTEM.write(out) { write(bytes) } }
        val back = codec.read(Buffer().write(bytes).asInputStream())
        assertEquals(2, back.pages.size)
        assertEquals(50, (back.pages[0].items[0] as Stroke).samples.size)
        assertEquals("Текст 😀", (back.pages[0].items[1] as TextItem).text)
    }

    @Test fun instantsAreWrittenAsJavaTimeWritesThem() {
        // Values printed by java.time.Instant.ofEpochMilli(ms).toString().
        val expected = mapOf(
            0L to "1970-01-01T00:00:00Z",
            1234L to "1970-01-01T00:00:01.234Z",
            1_700_000_000_000L to "2023-11-14T22:13:20Z",
            1_700_000_000_120L to "2023-11-14T22:13:20.120Z",
            -1L to "1969-12-31T23:59:59.999Z",
            1_790_000_000_001L to "2026-09-21T14:13:20.001Z",
        )
        for ((ms, text) in expected) {
            assertEquals(text, formatInstant(ms), "format $ms")
            assertEquals(ms, parseInstant(text), "parse $text")
        }
        assertNull(parseInstant("not a time"))
    }

    @Test fun platformHelpersBehave() {
        assertEquals("12.5", formatDecimal(12.46, 1))
        assertEquals("-3", formatDecimal(-2.5, 0))
        assertEquals("0.00", formatDecimal(0.001, 2))
        assertEquals(3L, roundHalfUp(2.5))
        assertEquals(-2L, roundHalfUp(-2.5))
        assertEquals(0L, roundHalfUp(Double.NaN))
        val a = "k".repeat(3)
        val b = "k".repeat(3)
        val map = identityMap<String, Int>()
        map[a] = 1
        map[b] = 2
        assertEquals(2, map.size)
        assertEquals(1, map[a])
        map.remove(a)
        assertEquals(listOf(2), map.values.toList())
        val lock = Lock()
        assertEquals(5, withLock(lock) { withLock(lock) { 5 } })
        val ids = IdCounter()
        assertEquals(listOf(1L, 2L), listOf(ids.next(), ids.next()))
        val t0 = monotonicNanos()
        assertTrue(monotonicNanos() >= t0)
        assertNotEquals(0, "x".hashCode())
    }

    private companion object {
        /** A note (strokes, images, JSON escapes, emoji, a bookmark) written by the JVM codec. */
        const val JVM_NOTE = "UEsDBAoAAAgAAKZ6O10P+PktBQAAAAUAAAAUAAAAYXNzZXRzL2ltYWdlLTAwMC5wbmcJCAcGBVBLAwQKAAAIAACmejtdB1q5WCkAAAApAAAAFAAAAGFzc2V0cy9pbWFnZS0wMDEuc3ZnPHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnLz5QSwMEFAAICAgApno7XQAAAAAAAAAAAAAAAA0AAABtYW5pZmVzdC5qc29upZVNbtswEIWvYnDNKqSoP+sqkSHQNiULliWBpJO0RoCgp+gudyhQoIsewrlJj9AZjpK6aIMGLZDE0Yj83uPLcHJizWgP2rOS3Q2jN4yzG2NdNw6slJzd2s4by8qk4Gw7dVBLBWc77epp27Cy0b0znK3HcX/Qdu9YeX1ik24NK2FZr9emB/D58enh/PX8+fzl6SNfVOz87fz16aFiVbWorhaVX3i9rmw1VEchhMSfsll8f/z0sDDDlt2veEAS/Lbb+h3YiBP0Ybp2B9ZlniawaNvUpD0c+54zcH6gTfsOOCVz3o57PKEfR/S10X3ftVZPu/dQ3IxD07WsPLG1dqaehTLgWuPc0ZraDHrdGyB5e4RTv9QP3VA3euNHCEpE4GTbWbPxkGENkmZo0bGIAGXbtYaMBI+V4FIWPE7T1T1nTh+m3qDZawkvBReRWvFrqSKpeCwjCYUEC1kUpzyOoxgKKRaWkQKKihQUMijAO1iQgAsR5ficRhkgUqgSMy6iHFZkYIeYSkYFIFATiQpekiDiVBEckCDyEhkckCB6TJLggBTRY5KjAxJEYArGwAEJIjNVwQEKIjHNgj5pIjGDBEgQcRllQILIyygDEkReRhmQIhLzkAEJIjCnDFAQiTklQIJILCgB0kRiAQcgQcQVlAEJIm9JGZAgEpeUASkicRkyQEHESUERkCIipaAMSBKZUlAMJItQiZ1AooiUczOQasDO3UCyATu3AwoHKHUD6Qbm3A6kG7BzP5BuwM4NQdL5CvrzxF65QZMZXr056uKGvO3mQOv9+eq88eLA9ICkodG5/MV1d8CJxJl2zuCgC5+uugr1d7hnGlp4j5cWrmDKM54lPClg8Di7qW9ZCY/hV7jHOAn10PY44qL0Ipu/qcjI3VyoSB5z+AtL8VMFHp9VpLgge3Pnwd404oCAZoLJgbvmCRXjRA5L/nfSvsQcqwwuwPwNEwq1u8HXrvsAx5bqwpvb6Qmzpc8yZEjxQM1ri4FCOwICZzmEK7gKRw6zuJ4HIoxBvozxCwci7sRJ/TyDoZOaru9/X5wlq/vQnv/wXwF3/gBQSwcIgz1ElCADAAD9BgAAUEsBAgoACgAACAAApno7XQ/4+S0FAAAABQAAABQAAAAAAAAAAAAAAAAAAAAAAGFzc2V0cy9pbWFnZS0wMDAucG5nUEsBAgoACgAACAAApno7XQdauVgpAAAAKQAAABQAAAAAAAAAAAAAAAAANwAAAGFzc2V0cy9pbWFnZS0wMDEuc3ZnUEsBAhQAFAAICAgApno7XYM9RJQgAwAA/QYAAA0AAAAAAAAAAAAAAAAAkgAAAG1hbmlmZXN0Lmpzb25QSwUGAAAAAAMAAwC/AAAA7QMAAAAA"
    }
}
