package com.xnotes

import com.xnotes.core.doubleCorpus
import com.xnotes.core.platform.javaDoubleToString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Manifests write doubles as java.lang.Double.toString does, on every platform. The digest was
 * taken from JDK 21's Double.toString over [doubleCorpus]; Kotlin/Native's own toString differs
 * from it above ~1e12, which is why the core formats through [javaDoubleToString].
 */
class DoubleTextTest {
    @Test fun formatsTheCorpusExactlyAsTheJdk() {
        val text = doubleCorpus().joinToString("\n") { javaDoubleToString(it) }
        assertEquals(JDK_DIGEST, text.encodeUtf8().sha256().hex())
    }

    @Test fun layoutMatchesTheJdk() {
        val cases = mapOf(
            0.001 to "0.001", 9.99e-4 to "9.99E-4", 1e7 to "1.0E7", 9999999.5 to "9999999.5", 100.0 to "100.0",
            0.25 to "0.25", 1e23 to "1.0E23", 4.9e-324 to "4.9E-324", -0.0 to "-0.0", 2.0 / 3 to "0.6666666666666666",
            1240.1574803149608 to "1240.1574803149608", -1.5e-7 to "-1.5E-7",
        )
        for ((v, text) in cases) assertEquals(text, javaDoubleToString(v), "$v")
    }

    private companion object {
        const val JDK_DIGEST = "3e1a737b87ac9592b5ec6b987a82f8526fc52e0ccaf5bae36bfc8f53b8b4f19f"
    }
}
