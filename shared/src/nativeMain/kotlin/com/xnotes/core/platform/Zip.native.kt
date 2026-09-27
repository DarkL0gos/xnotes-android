package com.xnotes.core.platform

// Filled in with the zlib implementation; stubs keep the build compiling meanwhile.
actual class ZipEntry actual constructor(private val name: String) {
    actual fun getName(): String = name
    actual fun isDirectory(): Boolean = name.endsWith("/")
    actual fun getMethod(): Int = TODO()
    actual fun setMethod(method: Int): Unit = TODO()
    actual fun setSize(size: Long): Unit = TODO()
    actual fun setCompressedSize(csize: Long): Unit = TODO()
    actual fun setCrc(crc: Long): Unit = TODO()
}

actual class ZipOutputStream actual constructor(out: OutputStream) : OutputStream(), AutoCloseable {
    override val sink: okio.Sink get() = TODO()
    actual fun setLevel(level: Int): Unit = TODO()
    actual fun putNextEntry(e: ZipEntry): Unit = TODO()
    actual fun closeEntry(): Unit = TODO()
    actual fun write(b: ByteArray, off: Int, len: Int): Unit = TODO()
    actual fun write(b: ByteArray): Unit = TODO()
    actual fun finish(): Unit = TODO()
    actual override fun close(): Unit = TODO()
}

actual class ZipInputStream actual constructor(input: InputStream) : InputStream(), AutoCloseable {
    override val source: okio.Source get() = TODO()
    actual fun getNextEntry(): ZipEntry? = TODO()
    actual fun closeEntry(): Unit = TODO()
    actual fun read(b: ByteArray, off: Int, len: Int): Int = TODO()
    actual override fun close(): Unit = TODO()
}

actual class CRC32 actual constructor() {
    actual fun update(b: ByteArray, off: Int, len: Int): Unit = TODO()
    actual fun getValue(): Long = TODO()
    actual fun reset(): Unit = TODO()
}

actual fun createTempFile(prefix: String, suffix: String?, dir: File): File = TODO()
