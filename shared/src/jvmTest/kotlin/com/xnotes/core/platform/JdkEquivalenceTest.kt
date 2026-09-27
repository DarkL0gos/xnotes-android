package com.xnotes.core.platform

import com.xnotes.core.geometry.toDegrees
import com.xnotes.core.geometry.toRadians
import com.xnotes.core.model.Rgba
import com.xnotes.format.SvgColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The common replacements for JDK calls must give the JVM exactly what the JDK gave, since files
 * are written with some of them and Android keeps running on the JVM.
 */
class JdkEquivalenceTest {
    private val doubles = listOf(
        0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994,
        1e-300, 123.456, -123.456, 1e15 + 0.5, 4.5e15, 9.3e18, -9.3e18, 1e300, -1e300,
        Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
    ) + (0 until 2000).map { (it - 1000) * 0.0137 + 0.005 }

    @Test fun roundHalfUpIsMathRound() {
        for (x in doubles) assertEquals("round($x)", Math.round(x), roundHalfUp(x))
    }

    @Test fun anglesAreBitIdentical() {
        for (x in doubles) {
            assertEquals("toRadians($x)", Math.toRadians(x).toRawBits(), toRadians(x).toRawBits())
            assertEquals("toDegrees($x)", Math.toDegrees(x).toRawBits(), toDegrees(x).toRawBits())
        }
    }

    @Test fun hexColourMatchesFormat() {
        for (v in listOf(0, 5, 15, 16, 128, 255, 256, 4095, -1, -300)) {
            val c = Rgba(v, 255 - v, v / 2)
            assertEquals("#%02x%02x%02x".format(c.r, c.g, c.b), Rgba.toHex(c))
        }
    }

    @Test fun svgHexDigitsMatchCharacterDigit() {
        // Ids that Character.digit accepts beyond ASCII: fullwidth digits and letters.
        for (s in listOf("#0f0", "#ABCDEF", "#12345678", "#g00", "#ｆｆ０", "#１２３", "#12")) {
            val body = s.substring(1)
            val jdkValid = body.all { Character.digit(it, 16) >= 0 }
            val parsed = SvgColors.parse(s)
            if (!jdkValid) assertEquals("rejects $s", null, parsed)
        }
        assertEquals(Rgba(0xab, 0xcd, 0xef), SvgColors.parse("#abcdef"))
        assertEquals(Rgba(0xff, 0xff, 0x00), SvgColors.parse("#ｆｆ０"))
    }

    @Test fun formatDecimalIsStringFormatOnTheJvm() {
        for (x in doubles) for (d in 0..2) assertEquals("%.${d}f".format(x), formatDecimal(x, d))
    }

    @Test fun identityMapComparesByReference() {
        val a = "key".repeat(2)
        val b = "key".repeat(2)
        val map = identityMap<String, Int>()
        map[a] = 1
        map[b] = 2
        assertEquals(2, map.size)
        assertEquals(1, map[a])
        assertEquals(2, map[b])
    }

    @Test fun lockIsReentrant() {
        val lock = Lock()
        assertEquals(3, withLock(lock) { withLock(lock) { 3 } })
    }

    @Test fun idsIncrease() {
        val counter = IdCounter()
        assertEquals(listOf(1L, 2L, 3L), List(3) { counter.next() })
    }

    @Test fun runnableIsJavasOnTheJvm() {
        val task = Runnable { }
        val jdk: java.lang.Runnable = task
        assertSame(task, jdk)
        assertNotSame(task, Runnable { })
    }
}
