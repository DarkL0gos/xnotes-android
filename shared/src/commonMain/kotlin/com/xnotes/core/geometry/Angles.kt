package com.xnotes.core.geometry

/** Degrees → radians, bit-for-bit what `java.lang.Math.toRadians` returns (same constant, one multiply). */
fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

/** Radians → degrees, bit-for-bit what `java.lang.Math.toDegrees` returns. */
fun toDegrees(radians: Double): Double = radians * RADIANS_TO_DEGREES

private const val DEGREES_TO_RADIANS = 0.017453292519943295
private const val RADIANS_TO_DEGREES = 57.29577951308232
