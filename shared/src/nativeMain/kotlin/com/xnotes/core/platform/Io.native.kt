package com.xnotes.core.platform

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source

actual typealias File = Path

actual fun fileOf(path: String): File = path.toPath()

actual fun fileOf(parent: File, child: String): File = parent / child

actual val File.pathString: String get() = toString()

actual fun File.canonicalPathString(): String {
    val absolute = if (isAbsolute) this else FileSystem.SYSTEM.canonicalize(".".toPath()) / this
    return runCatching { FileSystem.SYSTEM.canonicalize(absolute) }.getOrElse { absolute.normalized() }.toString()
}

actual fun File.asPath(): Path = this

actual fun Path.asFile(): File = this

/** A byte stream the core reads through [source]. */
actual abstract class InputStream {
    internal abstract val source: Source
}

/** A byte stream the core writes through [sink]. */
actual abstract class OutputStream {
    internal abstract val sink: Sink
}

private class SourceInputStream(override val source: Source) : InputStream()

private class SinkOutputStream(override val sink: Sink) : OutputStream()

actual fun InputStream.asSource(): Source = source

actual fun OutputStream.asSink(): Sink = sink

actual fun Source.asInputStream(): InputStream = SourceInputStream(this)

actual fun Sink.asOutputStream(): OutputStream = SinkOutputStream(this)

