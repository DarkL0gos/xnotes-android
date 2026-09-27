package com.xnotes.core

import kotlin.random.Random

/**
 * Doubles of every magnitude a manifest can hold (coordinates, widths, pressures, zooms, tiny and
 * huge values), the same on every platform: kotlin.random is one algorithm everywhere.
 */
fun doubleCorpus(): List<Double> {
    val r = Random(20260927)
    val out = ArrayList<Double>()
    val fixed = listOf(0.1, 0.2, 0.3, 1.0 / 3, 2.0 / 3, 1e-3, 1e-4, 9.999e-4, 1e6, 9_999_999.5, 1e7, 1.5e7, 1e21, 1e22, 1e23,
        123456.789, 1240.1574803149608, 1753.9370078740158, 4.9e-324, Double.MAX_VALUE, 2.2250738585072014E-308, 0.5, 100.0 / 7)
    out += fixed
    out += fixed.map { -it }
    repeat(20_000) {
        val magnitude = r.nextInt(-12, 24)
        out += r.nextDouble() * 10.0.pow(magnitude) * (if (r.nextBoolean()) 1 else -1)
    }
    repeat(5_000) { out += Double.fromBits(r.nextLong()).takeIf { d -> d.isFinite() } ?: 1.0 }
    repeat(5_000) { out += (r.nextInt(-100_000, 100_000) / 100.0) } // rounded coordinates, as samples are
    return out
}

private fun Double.pow(n: Int): Double {
    var v = 1.0
    val base = if (n >= 0) this else 1 / this
    repeat(kotlin.math.abs(n)) { v *= base }
    return v
}
