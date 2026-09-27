package com.xnotes.core.platform

import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source

actual typealias File = java.io.File

actual fun fileOf(path: String): File = java.io.File(path)

actual fun fileOf(parent: File, child: String): File = java.io.File(parent, child)

actual val File.pathString: String get() = path

actual fun File.canonicalPathString(): String = canonicalPath

actual fun File.asPath(): Path = toOkioPath()

actual fun Path.asFile(): File = toFile()

actual typealias InputStream = java.io.InputStream

actual typealias OutputStream = java.io.OutputStream

actual fun InputStream.asSource(): Source = source()

actual fun OutputStream.asSink(): Sink = sink()

actual fun Source.asInputStream(): InputStream = buffer().inputStream()

actual fun Sink.asOutputStream(): OutputStream = buffer().outputStream()
