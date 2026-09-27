package com.xnotes.bench

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Stage 4.4 measurement on the JVM; runs only with XNOTES_BENCH=1. */
class PenBenchJvmTest {
    @Test fun report() {
        assumeTrue(System.getenv("XNOTES_BENCH") == "1")
        for (r in PenBench.all()) println("PENBENCH jvm    " + r.report() + " (checksum ${r.checksum})")
    }
}
