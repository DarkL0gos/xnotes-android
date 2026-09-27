package com.xnotes.core.platform

actual fun formatInstant(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).toString()

actual fun parseInstant(text: String): Long? = runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
