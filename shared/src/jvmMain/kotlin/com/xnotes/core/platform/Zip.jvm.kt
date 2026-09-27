package com.xnotes.core.platform

actual typealias ZipEntry = java.util.zip.ZipEntry

actual typealias ZipOutputStream = java.util.zip.ZipOutputStream

actual typealias ZipInputStream = java.util.zip.ZipInputStream

actual typealias CRC32 = java.util.zip.CRC32

actual fun createTempFile(prefix: String, suffix: String?, dir: File): File = java.io.File.createTempFile(prefix, suffix, dir)
