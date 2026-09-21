package com.xnotes.core.util

/** Minimal, platform-neutral path-name helpers (no java.io dependency). */
object Paths {
    fun baseName(path: String): String {
        // SAF content URIs encode the path inside the last URL segment
        // (e.g. ".../document/primary%3ADocs%2FNote.xnote"); decode first so the
        // real separators surface, then split on path / document-id delimiters.
        val decoded = percentDecode(path)
        val i = decoded.lastIndexOfAny(charArrayOf('/', '\\', ':'))
        return if (i >= 0) decoded.substring(i + 1) else decoded
    }

    /** File base name without its extension. */
    fun stem(path: String): String {
        val base = baseName(path)
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }

    /** Decodes `%XX` escapes (UTF-8) and `+` as space; leaves other text intact. */
    private fun percentDecode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hi = hex(s[i + 1]); val lo = hex(s[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        bytes.add(((hi shl 4) or lo).toByte()); i += 3
                    } else {
                        bytes.add(c.code.toByte()); i++
                    }
                }
                c == '+' -> { bytes.add(' '.code.toByte()); i++ }
                else -> { for (b in c.toString().encodeToByteArray()) bytes.add(b); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
