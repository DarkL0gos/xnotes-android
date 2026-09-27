@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.core.platform

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import platform.posix.localtime_r
import platform.posix.memset
import platform.posix.time_tVar
import platform.posix.tm
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.crc32
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import kotlin.random.Random

/*
 * java.util.zip for Kotlin/Native, over the system zlib. Archives are laid out the way
 * java.util.zip.ZipOutputStream lays them out (same flags, versions, headers, data descriptors and
 * deflate parameters), so a bundle written here reads everywhere a JVM-written one does. No ZIP64:
 * a note never approaches 4 GB, and oversized input fails loudly instead of writing a broken file.
 */

/** A ZIP format problem (`java.util.zip.ZipException`). */
class ZipException(message: String) : IOException(message)

private const val LOCSIG = 0x04034b50L
private const val EXTSIG = 0x08074b50L
private const val CENSIG = 0x02014b50L
private const val ENDSIG = 0x06054b50L
private const val FLAG_DESCRIPTOR = 0x0008
private const val FLAG_UTF8 = 0x0800
private const val MAX_U32 = 0xFFFFFFFFL
private const val MEM_LEVEL = 8 // zlib's DEF_MEM_LEVEL, what java.util.zip.Deflater uses
private const val MAX_WBITS = 15 // zconf.h; negated, it selects raw deflate (java.util.zip's nowrap)

actual class ZipEntry actual constructor(private val name: String) {
    internal var method = -1
    internal var size = -1L
    internal var csize = -1L
    internal var crc = -1L
    internal var flag = 0
    internal var dosTime = -1L

    init {
        require(name.encodeToByteArray().size <= 0xFFFF) { "entry name too long" }
    }

    actual fun getName(): String = name
    actual fun isDirectory(): Boolean = name.endsWith("/")
    actual fun getMethod(): Int = method
    actual fun setMethod(method: Int) {
        require(method == ZipMethod.STORED || method == ZipMethod.DEFLATED) { "invalid compression method" }
        this.method = method
    }

    actual fun setSize(size: Long) {
        require(size in 0..MAX_U32) { "invalid entry size" }
        this.size = size
    }

    actual fun setCompressedSize(csize: Long) {
        this.csize = csize
    }

    actual fun setCrc(crc: Long) {
        require(crc in 0..MAX_U32) { "invalid entry crc-32" }
        this.crc = crc
    }
}

actual class CRC32 actual constructor() {
    private var value = 0L

    actual fun update(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off + len <= b.size) { "bad range" }
        if (len == 0) return
        b.usePinned { pinned ->
            value = crc32(value.convert(), pinned.addressOf(off).reinterpret<UByteVar>(), len.convert()).toLong()
        }
    }

    actual fun getValue(): Long = value
    actual fun reset() {
        value = 0L
    }
}

/** The current local time as an MS-DOS date/time, as java.util.zip stamps entries. */
private fun dosTimeNow(): Long = memScoped {
    val now = alloc<time_tVar>()
    now.value = platform.posix.time(null)
    val t = alloc<tm>()
    localtime_r(now.ptr, t.ptr)
    val year = t.tm_year + 1900
    if (year < 1980) return (1L shl 21) or (1L shl 16) // 1980-01-01, the earliest DOS date
    (((year - 1980).toLong() shl 25) or ((t.tm_mon + 1).toLong() shl 21) or (t.tm_mday.toLong() shl 16) or
        (t.tm_hour.toLong() shl 11) or (t.tm_min.toLong() shl 5) or (t.tm_sec.toLong() shr 1))
}

private fun newStream(): CPointer<z_stream> {
    val s = nativeHeap.alloc<z_stream>()
    memset(s.ptr, 0, sizeOf<z_stream>().convert())
    return s.ptr
}

private fun BufferedSink.u16(v: Int) = writeShortLe(v)
private fun BufferedSink.u32(v: Long) = writeIntLe(v.toInt())

actual class ZipOutputStream actual constructor(out: OutputStream) : OutputStream(), AutoCloseable {
    private val target: BufferedSink = out.asSink().buffer()
    private var level = Z_DEFAULT_COMPRESSION
    private var current: ZipEntry? = null
    private var currentOffset = 0L
    private var written = 0L
    private val entries = ArrayList<Pair<ZipEntry, Long>>()
    private val names = HashSet<String>()
    private val crc = CRC32()
    private var entryBytes = 0L
    private var deflater: CPointer<z_stream>? = null
    private val outBuf = ByteArray(64 * 1024)
    private var finished = false
    private var closed = false

    override val sink: Sink = object : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            var left = byteCount
            val chunk = ByteArray(8192)
            while (left > 0) {
                val n = source.read(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
                if (n < 0) throw IOException("source exhausted")
                this@ZipOutputStream.write(chunk, 0, n)
                left -= n
            }
        }

        override fun flush() = target.flush()
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = this@ZipOutputStream.close()
    }

    actual fun setLevel(level: Int) {
        require(level in -1..9) { "invalid compression level" }
        this.level = level
    }

    actual fun putNextEntry(e: ZipEntry) {
        ensureOpen()
        if (current != null) closeEntry()
        if (e.dosTime == -1L) e.dosTime = dosTimeNow()
        if (e.method == -1) e.method = ZipMethod.DEFLATED
        e.flag = 0
        when (e.method) {
            ZipMethod.DEFLATED -> if (e.size == -1L || e.csize == -1L || e.crc == -1L) e.flag = FLAG_DESCRIPTOR
            ZipMethod.STORED -> {
                if (e.size == -1L) e.size = e.csize else if (e.csize == -1L) e.csize = e.size
                else if (e.size != e.csize) throw ZipException("STORED entry where compressed != uncompressed size")
                if (e.size == -1L || e.crc == -1L) throw ZipException("STORED entry missing size, compressed size, or crc-32")
            }
        }
        if (!names.add(e.getName())) throw ZipException("duplicate entry: ${e.getName()}")
        e.flag = e.flag or FLAG_UTF8
        current = e
        currentOffset = written
        writeLocalHeader(e)
        crc.reset()
        entryBytes = 0L
        if (e.method == ZipMethod.DEFLATED) {
            val s = newStream()
            val rc = deflateInit2_(s, level, Z_DEFLATED, -MAX_WBITS, MEM_LEVEL, Z_DEFAULT_STRATEGY,
                ZLIB_VERSION, sizeOf<z_stream>().toInt())
            if (rc != Z_OK) throw ZipException("deflateInit2 failed: $rc")
            deflater = s
        }
    }

    actual fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        require(off >= 0 && len >= 0 && off + len <= b.size) { "bad range" }
        val e = current ?: throw ZipException("no current ZIP entry")
        if (len == 0) return
        crc.update(b, off, len)
        entryBytes += len
        when (e.method) {
            ZipMethod.STORED -> {
                if (entryBytes > e.size) throw ZipException("attempt to write past end of STORED entry")
                target.write(b, off, len)
                written += len
            }
            else -> b.usePinned { pinned ->
                val s = deflater!!.pointed
                s.next_in = pinned.addressOf(off).reinterpret()
                s.avail_in = len.convert()
                pump(Z_NO_FLUSH)
            }
        }
    }

    actual fun write(b: ByteArray) = write(b, 0, b.size)

    /** Run the deflater with [flush] until it wants more input (or, for Z_FINISH, has ended). */
    private fun pump(flush: Int) {
        val s = deflater!!.pointed
        outBuf.usePinned { outPinned ->
            while (true) {
                s.next_out = outPinned.addressOf(0).reinterpret()
                s.avail_out = outBuf.size.convert()
                val rc = deflate(s.ptr, flush)
                val produced = outBuf.size - s.avail_out.toInt()
                if (produced > 0) {
                    target.write(outBuf, 0, produced)
                    written += produced
                }
                if (flush == Z_FINISH) {
                    if (rc == Z_STREAM_END) return
                    if (rc != Z_OK && rc != Z_BUF_ERROR) throw ZipException("deflate failed: $rc")
                } else {
                    if (rc != Z_OK && rc != Z_BUF_ERROR) throw ZipException("deflate failed: $rc")
                    if (s.avail_in.toInt() == 0 && s.avail_out.toInt() != 0) return
                }
            }
        }
    }

    actual fun closeEntry() {
        ensureOpen()
        val e = current ?: return
        when (e.method) {
            ZipMethod.DEFLATED -> {
                pump(Z_FINISH)
                val s = deflater!!
                val compressed = s.pointed.total_out.toLong()
                deflateEnd(s)
                nativeHeap.free(s.rawValue)
                deflater = null
                if (e.flag and FLAG_DESCRIPTOR == 0) {
                    if (e.size != entryBytes) throw ZipException("invalid entry size (expected ${e.size} but got $entryBytes bytes)")
                    if (e.csize != compressed) throw ZipException("invalid entry compressed size (expected ${e.csize} but got $compressed bytes)")
                    if (e.crc != crc.getValue()) throw ZipException("invalid entry CRC-32")
                } else {
                    e.size = entryBytes
                    e.csize = compressed
                    e.crc = crc.getValue()
                    if (e.size > MAX_U32 || e.csize > MAX_U32) throw ZipException("entry too large without ZIP64")
                    target.u32(EXTSIG)
                    target.u32(e.crc)
                    target.u32(e.csize)
                    target.u32(e.size)
                    written += 16
                }
            }
            else -> {
                if (entryBytes != e.size) throw ZipException("invalid entry size (expected ${e.size} but got $entryBytes bytes)")
                if (crc.getValue() != e.crc) throw ZipException("invalid entry crc-32 (expected ${e.crc} but got ${crc.getValue()})")
            }
        }
        entries += e to currentOffset
        current = null
    }

    private fun version(e: ZipEntry) = if (e.method == ZipMethod.DEFLATED) 20 else 10

    private fun writeLocalHeader(e: ZipEntry) {
        val name = e.getName().encodeToByteArray()
        target.u32(LOCSIG)
        target.u16(version(e))
        target.u16(e.flag)
        target.u16(e.method)
        target.u32(e.dosTime)
        if (e.flag and FLAG_DESCRIPTOR != 0) {
            target.u32(0)
            target.u32(0)
            target.u32(0)
        } else {
            target.u32(e.crc)
            target.u32(e.csize)
            target.u32(e.size)
        }
        target.u16(name.size)
        target.u16(0)
        target.write(name)
        written += 30 + name.size
    }

    actual fun finish() {
        ensureOpen()
        if (finished) return
        if (current != null) closeEntry()
        val cenStart = written
        for ((e, offset) in entries) {
            if (offset > MAX_U32) throw ZipException("archive too large without ZIP64")
            val name = e.getName().encodeToByteArray()
            target.u32(CENSIG)
            target.u16(version(e)) // version made by
            target.u16(version(e)) // version needed to extract
            target.u16(e.flag)
            target.u16(e.method)
            target.u32(e.dosTime)
            target.u32(e.crc)
            target.u32(e.csize)
            target.u32(e.size)
            target.u16(name.size)
            target.u16(0) // extra length
            target.u16(0) // comment length
            target.u16(0) // disk number start
            target.u16(0) // internal attributes
            target.u32(0) // external attributes
            target.u32(offset)
            target.write(name)
            written += 46 + name.size
        }
        val cenSize = written - cenStart
        if (entries.size > 0xFFFF || cenStart > MAX_U32) throw ZipException("archive too large without ZIP64")
        target.u32(ENDSIG)
        target.u16(0)
        target.u16(0)
        target.u16(entries.size)
        target.u16(entries.size)
        target.u32(cenSize)
        target.u32(cenStart)
        target.u16(0) // comment length
        written += 22
        target.flush()
        finished = true
    }

    actual override fun close() {
        if (closed) return
        try {
            finish()
        } finally {
            deflater?.let {
                deflateEnd(it)
                nativeHeap.free(it.rawValue)
            }
            deflater = null
            closed = true
            target.close()
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }
}

actual class ZipInputStream actual constructor(input: InputStream) : InputStream(), AutoCloseable {
    private val src: BufferedSource = input.asSource().buffer()
    private var entry: ZipEntry? = null
    private var entryEof = true
    private var remaining = 0L
    private var inflater: CPointer<z_stream>? = null
    private val crc = CRC32()
    private var produced = 0L
    private var closed = false
    private val inBuf = ByteArray(16 * 1024)

    override val source: Source = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val chunk = ByteArray(minOf(byteCount, 8192L).toInt())
            val n = this@ZipInputStream.read(chunk, 0, chunk.size)
            if (n < 0) return -1
            sink.write(chunk, 0, n)
            return n.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = this@ZipInputStream.close()
    }

    actual fun getNextEntry(): ZipEntry? {
        ensureOpen()
        if (entry != null) closeEntry()
        if (!src.request(4)) return null
        if (src.buffer.readIntLe().toLong() and MAX_U32 != LOCSIG) return null // central directory: no more entries
        src.require(26)
        src.readShortLe() // version needed
        val flag = src.readShortLe().toInt() and 0xFFFF
        val method = src.readShortLe().toInt() and 0xFFFF
        val dosTime = src.readIntLe().toLong() and MAX_U32
        val crcField = src.readIntLe().toLong() and MAX_U32
        val csize = src.readIntLe().toLong() and MAX_U32
        val size = src.readIntLe().toLong() and MAX_U32
        val nameLen = src.readShortLe().toInt() and 0xFFFF
        val extraLen = src.readShortLe().toInt() and 0xFFFF
        val name = src.readUtf8(nameLen.toLong())
        src.skip(extraLen.toLong())
        val e = ZipEntry(name)
        e.flag = flag
        e.dosTime = dosTime
        e.method = method
        if (flag and FLAG_DESCRIPTOR == 0) {
            e.crc = crcField
            e.csize = csize
            e.size = size
        }
        crc.reset()
        produced = 0L
        when (method) {
            ZipMethod.STORED -> {
                if (flag and FLAG_DESCRIPTOR != 0) throw ZipException("only DEFLATED entries can have EXT descriptor")
                remaining = size
            }
            ZipMethod.DEFLATED -> {
                val s = newStream()
                val rc = inflateInit2_(s, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
                if (rc != Z_OK) throw ZipException("inflateInit2 failed: $rc")
                inflater = s
            }
            else -> throw ZipException("invalid compression method")
        }
        entry = e
        entryEof = false
        if (method == ZipMethod.STORED && remaining == 0L) finishEntry()
        return e
    }

    actual fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureOpen()
        require(off >= 0 && len >= 0 && off + len <= b.size) { "bad range" }
        if (len == 0) return 0
        val e = entry ?: return -1
        if (entryEof) return -1
        return if (e.method == ZipMethod.STORED) readStored(b, off, len) else readDeflated(b, off, len)
    }

    private fun readStored(b: ByteArray, off: Int, len: Int): Int {
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw IOException("unexpected EOF")
        crc.update(b, off, n)
        remaining -= n
        if (remaining == 0L) finishEntry()
        return n
    }

    private fun readDeflated(b: ByteArray, off: Int, len: Int): Int {
        val s = inflater!!.pointed
        while (true) {
            if (!src.request(1)) throw IOException("Unexpected end of ZLIB input stream")
            // Feed without consuming: the stream's end may fall inside this chunk, and whatever
            // follows it (a data descriptor, the next header) must stay in the source.
            val avail = minOf(src.buffer.size, inBuf.size.toLong()).toInt()
            src.peek().read(inBuf, 0, avail)
            var rc = 0
            var out = 0
            var used = 0
            inBuf.usePinned { inPinned ->
                b.usePinned { outPinned ->
                    s.next_in = inPinned.addressOf(0).reinterpret()
                    s.avail_in = avail.convert()
                    s.next_out = outPinned.addressOf(off).reinterpret()
                    s.avail_out = len.convert()
                    rc = inflate(s.ptr, Z_NO_FLUSH)
                    used = avail - s.avail_in.toInt()
                    out = len - s.avail_out.toInt()
                }
            }
            src.skip(used.toLong())
            if (rc != Z_OK && rc != Z_STREAM_END && rc != Z_BUF_ERROR) throw ZipException("invalid deflate data: $rc")
            if (out > 0) {
                crc.update(b, off, out)
                produced += out
            }
            if (rc == Z_STREAM_END) {
                finishEntry()
                return if (out > 0) out else -1
            }
            if (out > 0) return out
        }
    }

    /** The entry's data is done: read its descriptor if it has one, and check what was read against it. */
    private fun finishEntry() {
        val e = entry ?: return
        inflater?.let {
            val compressed = it.pointed.total_in.toLong()
            inflateEnd(it)
            nativeHeap.free(it.rawValue)
            inflater = null
            if (e.flag and FLAG_DESCRIPTOR != 0) {
                src.require(4)
                var first = src.readIntLe().toLong() and MAX_U32
                if (first == EXTSIG) {
                    src.require(4)
                    first = src.readIntLe().toLong() and MAX_U32
                }
                src.require(8)
                e.crc = first
                e.csize = src.readIntLe().toLong() and MAX_U32
                e.size = src.readIntLe().toLong() and MAX_U32
            }
            if (e.size != produced) throw ZipException("invalid entry size (expected ${e.size} but got $produced bytes)")
            if (e.csize != compressed) throw ZipException("invalid entry compressed size (expected ${e.csize} but got $compressed bytes)")
        }
        if (e.crc != crc.getValue()) throw ZipException("invalid entry CRC (expected 0x${e.crc.toString(16)} but got 0x${crc.getValue().toString(16)})")
        entryEof = true
    }

    actual fun closeEntry() {
        ensureOpen()
        if (entry == null) return
        val skip = ByteArray(8192)
        while (!entryEof) read(skip, 0, skip.size)
        entry = null
    }

    actual override fun close() {
        if (closed) return
        closed = true
        inflater?.let {
            inflateEnd(it)
            nativeHeap.free(it.rawValue)
        }
        inflater = null
        src.close()
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }
}

actual fun createTempFile(prefix: String, suffix: String?, dir: File): File {
    require(prefix.length >= 3) { "Prefix string too short" }
    val fs = FileSystem.SYSTEM
    repeat(100) {
        val name = prefix + Random.nextLong().toULong().toString() + (suffix ?: ".tmp")
        val path = dir / name
        try {
            fs.openReadWrite(path, mustCreate = true, mustExist = false).close()
            return path
        } catch (_: IOException) {
            // taken (or unwritable): try another name, then give up below
        }
    }
    throw IOException("could not create a temp file in $dir")
}
