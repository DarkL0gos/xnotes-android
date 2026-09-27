package com.xnotes.core.platform

import kotlin.math.roundToLong

/**
 * `java.lang.Math.round(double)` on every target: nearest integer with ties towards positive
 * infinity, clamped to the Long range, and 0 for NaN (where [roundToLong] would throw).
 */
fun roundHalfUp(x: Double): Long = if (x.isNaN()) 0L else x.roundToLong()
