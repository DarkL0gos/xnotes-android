@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.core.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.LC_ALL_MASK
import platform.posix.newlocale
import platform.posix.snprintf
import platform.posix.strtod
import platform.posix.uselocale

/*
 * Kotlin/Native's Double.toString is not the JDK's: above ~1e12 it sometimes picks another last
 * digit, and a few of its strings do not even read back as the same double. This reproduces the
 * JDK's choice from glibc's correctly rounded printf and strtod.
 */
actual fun javaDoubleToString(v: Double): String {
    if (v.isNaN()) return "NaN"
    if (v == Double.POSITIVE_INFINITY) return "Infinity"
    if (v == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (v == 0.0) return if (1.0 / v < 0) "-0.0" else "0.0"
    val (digits, exponent) = shortestDigits(v)
    return layout(v < 0, digits, exponent)
}

/*
 * printf and strtod follow LC_NUMERIC, which a host may set from the environment (Qt does): under
 * ru_RU they would write "1,5". The conversion runs in the C locale, on this thread only.
 */
private val cLocale = newlocale(LC_ALL_MASK, "C", null)

/** The significant digits (no sign, no trailing zeros beyond two digits) and the decimal exponent of the first. */
private fun shortestDigits(v: Double): Pair<String, Int> {
    val previous = uselocale(cLocale)
    try {
        return shortestDigitsInLocale(v)
    } finally {
        uselocale(previous)
    }
}

private fun shortestDigitsInLocale(v: Double): Pair<String, Int> = memScoped {
    val buf = allocArray<ByteVar>(64)
    for (precision in 2..17) {
        snprintf(buf, 64u, "%.*e", precision - 1, v)
        val text = buf.toKString()
        if (strtod(text, null) == v) {
            val mantissa = text.substringBefore('e').removePrefix("-").replace(".", "")
            val exponent = text.substringAfter('e').toInt()
            return@memScoped mantissa.trimEnd('0').padEnd(2, '0').let { d ->
                // Keep only as many digits as the precision that round-tripped (zeros past two are dropped).
                d to exponent
            }
        }
    }
    error("no round-tripping decimal for $v")
}

private fun layout(negative: Boolean, digits: String, exponent: Int): String = buildString {
    if (negative) append('-')
    if (exponent in -3..6) {
        if (exponent >= 0) {
            val intLen = exponent + 1
            if (digits.length <= intLen) {
                append(digits.padEnd(intLen, '0')).append(".0")
            } else {
                append(digits, 0, intLen).append('.').append(digits, intLen, digits.length)
            }
        } else {
            append("0.")
            repeat(-exponent - 1) { append('0') }
            append(digits.trimEnd('0').ifEmpty { "0" })
        }
    } else {
        append(digits[0]).append('.')
        val rest = digits.substring(1).trimEnd('0')
        append(rest.ifEmpty { "0" })
        append('E').append(exponent)
    }
}
