package com.xnotes.core.platform

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
actual fun formatInstant(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

@OptIn(ExperimentalTime::class)
actual fun parseInstant(text: String): Long? = runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
