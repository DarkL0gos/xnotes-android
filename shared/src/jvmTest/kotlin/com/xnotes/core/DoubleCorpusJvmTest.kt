package com.xnotes.core

import com.xnotes.core.platform.javaDoubleToString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** Pins the JDK's Double.toString over [doubleCorpus]; the native DoubleTextTest must match this digest. */
class DoubleCorpusJvmTest {
    @Test fun theJdkDigestIsTheOneNativeIsCheckedAgainst() {
        val text = doubleCorpus().joinToString("\n") { javaDoubleToString(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("3e1a737b87ac9592b5ec6b987a82f8526fc52e0ccaf5bae36bfc8f53b8b4f19f", digest)
    }
}
