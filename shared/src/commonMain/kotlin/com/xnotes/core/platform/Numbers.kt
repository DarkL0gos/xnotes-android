package com.xnotes.core.platform

import kotlin.math.roundToLong

/**
 * `java.lang.Math.round(double)` on every target: nearest integer with ties towards positive
 * infinity, clamped to the Long range, and 0 for NaN (where [roundToLong] would throw).
 */
fun roundHalfUp(x: Double): Long = if (x.isNaN()) 0L else x.roundToLong()

/**
 * [v] as `java.lang.Double.toString` writes it (JDK 19+): the shortest decimal of at least two
 * digits that reads back as [v], plain for 1e-3 ≤ |v| < 1e7 and `d.dddE±n` otherwise. Manifests
 * carry doubles in this form, so every platform must produce the same text.
 */
expect fun javaDoubleToString(v: Double): String
