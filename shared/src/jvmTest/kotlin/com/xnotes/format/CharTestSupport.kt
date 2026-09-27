package com.xnotes.format

import okio.Buffer

/** A [CharSink] that keeps the characters, for tests that look at JSON text directly. */
internal class StringCharSink : CharSink {
    private val sb = StringBuilder()
    override fun write(s: String) { sb.append(s) }
    override fun write(c: Int) { sb.append(c.toChar()) }
    override fun write(chars: CharArray, offset: Int, length: Int) { sb.appendRange(chars, offset, offset + length) }
    override fun flush() = Unit
    override fun toString(): String = sb.toString()
}

/** [text] as a [CharSource], through the same UTF-8 decoder the codecs read with. */
internal fun stringSource(text: String): CharSource = Utf8CharSource(Buffer().writeUtf8(text))
