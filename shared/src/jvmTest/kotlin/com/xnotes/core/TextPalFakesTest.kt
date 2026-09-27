package com.xnotes.core

import com.xnotes.core.pal.FontSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the FakeTextMeasurer invariants every flow-layout test depends on:
 * deterministic monospace advances and metrics that sum to the line height
 * (the same relation the Android implementation guarantees by construction).
 */
class TextPalFakesTest {
    private val measurer = FakeTextMeasurer()
    private val font = FontSpec(10.0)

    @Test
    fun metricsSumToLineHeight() {
        val m = measurer.metrics(font)
        assertEquals(measurer.lineHeight(font), m.ascent + m.descent, 1e-9)
        assertEquals(measurer.lineHeight(font), m.height, 1e-9)
    }

    @Test
    fun advancesAreUniformPerCharacter() {
        val a = measurer.advances("hello world", font)
        assertEquals(11, a.size)
        assertEquals(10.0 * FakeTextMeasurer.ADVANCE_PER_POINT, a[0], 1e-9)
        assertEquals(a.sum(), a.size * a[0], 1e-9)
        assertEquals(0, measurer.advances("", font).size)
    }

    @Test
    fun rendererRecordsTextRuns() {
        val r = FakeRenderer()
        r.drawTextRun("hi", 4.0, 12.0, font, com.xnotes.core.model.Rgba(0, 0, 0, 255))
        assertEquals(listOf("drawTextRun:hi@4.0,12.0"), r.ops)
    }
}
