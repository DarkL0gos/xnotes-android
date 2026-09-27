package com.xnotes.format

import java.nio.channels.FileChannel

/*
 * Peeks through a FileChannel: the zip's central directory leads straight to the manifest, so
 * neither an embedded PDF nor any image is read. JVM-only because ZipTail reads positioned bytes
 * off a FileChannel, which is how the Android explorer opens documents.
 */

/**
 * A note's page count, PDF flag and created time, read from [ch] through the zip's central directory
 * straight to the manifest, so neither the embedded PDF nor any image is read. Null when [ch] is not a
 * note it can read that way (a pipe from a cloud provider, say), for [DocumentCodec.peek] from a stream instead.
 */
fun DocumentCodec.peek(ch: FileChannel): NotePeek? =
    runCatching { ZipTail.readEntry(ch, "manifest.json") { peekManifest(it) } }.getOrNull()

/**
 * A canvas's created time, read from [ch] through the zip's central directory straight to the head of
 * the manifest, so no item or image is read. Null when [ch] is not a canvas it can read that way, for
 * [CanvasCodec.peek] from a stream instead.
 */
fun CanvasCodec.peek(ch: FileChannel): CanvasPeek? =
    runCatching { ZipTail.readEntry(ch, "manifest.json") { peekManifest(it) } }.getOrNull()
