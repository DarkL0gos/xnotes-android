@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Stage 4.4 measurement on Kotlin/Native; runs only with XNOTES_BENCH=1. */
class PenBenchNativeTest {
    @Test fun report() {
        if (getenv("XNOTES_BENCH")?.toKString() != "1") return
        for (r in PenBench.all()) println("PENBENCH native " + r.report() + " (checksum ${r.checksum})")
    }
}
