package com.xnotes.core.platform

import okio.Path
import okio.Sink
import okio.Source

/*
 * Files and byte streams in the core's public API. On the JVM these are java.io's own classes, so
 * Android and the Swing host keep passing what they always passed; on Kotlin/Native a file is an
 * okio Path and a stream wraps an okio Source/Sink. Inside the core, IO goes through okio's
 * FileSystem.SYSTEM on both, reached via the conversions below.
 */

/** A file-system path (`java.io.File` on the JVM, `okio.Path` on native). */
expect class File

/** A file for [path], as given (`File(path)` on the JVM). */
expect fun fileOf(path: String): File

/** A child of [parent] (`File(parent, child)` on the JVM). */
expect fun fileOf(parent: File, child: String): File

/** The path as constructed (`File.path` on the JVM). */
expect val File.pathString: String

/** The absolute path with `.`/`..` resolved and, where it exists, links followed (`File.canonicalPath`). */
expect fun File.canonicalPathString(): String

expect fun File.asPath(): Path

expect fun Path.asFile(): File

/** An input byte stream (`java.io.InputStream` on the JVM). */
expect abstract class InputStream

/** An output byte stream (`java.io.OutputStream` on the JVM). */
expect abstract class OutputStream

/** Read [this] through okio. Closing the returned source closes the stream. */
expect fun InputStream.asSource(): Source

/** Write [this] through okio. Closing the returned sink closes the stream. */
expect fun OutputStream.asSink(): Sink

/** [this] as a stream for the core's stream-taking APIs. Closing the stream closes the source. */
expect fun Source.asInputStream(): InputStream

/** [this] as a stream for the core's stream-taking APIs. Closing the stream closes the sink. */
expect fun Sink.asOutputStream(): OutputStream
