package com.xnotes.core.platform

/** [epochMillis] as ISO-8601 in UTC, exactly as `java.time.Instant.ofEpochMilli(ms).toString()` writes it. */
expect fun formatInstant(epochMillis: Long): String

/** An ISO-8601 instant (as `java.time.Instant.parse` accepts it) in epoch milliseconds, or null. */
expect fun parseInstant(text: String): Long?
