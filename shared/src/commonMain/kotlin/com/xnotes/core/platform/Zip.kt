package com.xnotes.core.platform

/*
 * ZIP streams for the codecs, shaped like java.util.zip (the subset the codecs use). On the JVM they
 * are java.util.zip's own classes, so bundles keep their exact bytes and Android keeps handing its
 * own ZipOutputStream to DocumentCodec.writeTail; Kotlin/Native implements them over zlib.
 * Accessors are functions (getName(), setMethod()) because that is how Java declares them.
 */

/** ZIP compression methods (`ZipEntry.STORED` / `ZipEntry.DEFLATED`). */
object ZipMethod {
    const val STORED = 0
    const val DEFLATED = 8
}

/** `Deflater.BEST_SPEED`. */
const val DEFLATE_BEST_SPEED = 1

expect class ZipEntry(name: String) {
    fun getName(): String
    fun isDirectory(): Boolean
    fun getMethod(): Int
    fun setMethod(method: Int)
    fun setSize(size: Long)
    fun setCompressedSize(csize: Long)
    fun setCrc(crc: Long)
}

/** Writes a ZIP archive to the stream it wraps; entries default to DEFLATED. */
expect class ZipOutputStream(out: OutputStream) : OutputStream, AutoCloseable {
    fun setLevel(level: Int)
    fun putNextEntry(e: ZipEntry)
    fun closeEntry()
    fun write(b: ByteArray, off: Int, len: Int)
    fun write(b: ByteArray)
    fun finish()
    override fun close()
}

/** Reads a ZIP archive front to back; [read] returns -1 at the end of the current entry. */
expect class ZipInputStream(input: InputStream) : InputStream, AutoCloseable {
    fun getNextEntry(): ZipEntry?
    fun closeEntry()
    fun read(b: ByteArray, off: Int, len: Int): Int
    override fun close()
}

/** The ZIP checksum (`java.util.zip.CRC32`). */
expect class CRC32() {
    fun update(b: ByteArray, off: Int, len: Int)
    fun getValue(): Long
    fun reset()
}

/** A new empty file in [dir] named [prefix]…[suffix] (`File.createTempFile`; suffix null → ".tmp"). */
expect fun createTempFile(prefix: String, suffix: String?, dir: File): File
