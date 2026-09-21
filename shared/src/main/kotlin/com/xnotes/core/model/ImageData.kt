package com.xnotes.core.model

import java.io.File

/**
 * The immutable source of an [ImageItem]: the encoded image [file] on disk plus its native pixel
 * size. The renderer decodes from the file on demand at the resolution each draw needs, so a large
 * photo never sits decoded in memory and the encoded bytes never all sit in the heap either (a note
 * with many big images stays flat). Shared freely between copies because the file is read-only; the
 * file lives in a temp dir owned by the platform layer (purged on launch).
 */
class ImageData(
    val file: File,
    val width: Int,
    val height: Int,
)
