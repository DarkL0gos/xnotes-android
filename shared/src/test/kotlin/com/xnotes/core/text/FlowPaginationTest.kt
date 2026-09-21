package com.xnotes.core.text

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination against the fake measurer: at the 12pt default a char is 7.2 wide
 * and a line is 15.6 tall, so a margin-less 100x50 page holds 3 lines.
 */
class FlowPaginationTest {

    private val layout = FlowLayout(FakeTextMeasurer())

    private fun flowOf(vararg lines: String): TextFlow = TextFlow().apply {
        margins = FlowMargins(0.0, 0.0, 0.0, 0.0)
        lines.forEach {
            paragraphs.add(Paragraph(if (it.isEmpty()) mutableListOf() else mutableListOf(Run(it))))
        }
    }

    private fun pages(n: Int) = List(n) { PageBox(100.0, 50.0) }

    @Test
    fun fillsPagesTopToBottom() {
        val flow = flowOf(*Array(8) { "" })
        val frame = layout.layout(flow, pages(3), 150)
        assertEquals(listOf(3, 3, 2), frame.pages.map { it.lines.size })
        assertEquals(0.0, frame.pages[0].lines[0].top, 1e-9)
        assertEquals(15.6, frame.pages[0].lines[1].top, 1e-9)
        assertEquals(0.0, frame.pages[1].lines[0].top, 1e-9)
        assertEquals(0, frame.extraPagesNeeded)
        assertEquals(setOf(0, 1, 2), frame.pagesWithLines())
    }

    @Test
    fun overflowPastTheLastPageIsCounted() {
        val flow = flowOf(*Array(8) { "" })
        val frame = layout.layout(flow, pages(1), 150)
        assertEquals(3, frame.pages[0].lines.size)
        assertEquals(2, frame.extraPagesNeeded)
        assertEquals(0 to 46.8, frame.lastLineEnd())
    }

    @Test
    fun continuationRebreaksAtTheNextPageWidth() {
        val flow = flowOf("aaaa bbbb cccc dddd eeee")
        val boxes = listOf(PageBox(100.0, 16.0), PageBox(60.0, 100.0))
        val frame = layout.layout(flow, boxes, 150)
        assertEquals(1, frame.pages[0].lines.size)
        assertEquals(10, frame.pages[0].lines[0].endChar)
        assertEquals(listOf(10, 15, 20), frame.pages[1].lines.map { it.startChar })
        assertEquals(listOf(15, 20, 24), frame.pages[1].lines.map { it.endChar })
    }

    @Test
    fun justifyStretchesInteriorSpacesToTheMargin() {
        val flow = flowOf("aa bb cc dd ee")
        flow.paragraphs[0].align = ParaAlign.JUSTIFY
        val frame = layout.layout(flow, pages(1), 150)
        val first = frame.pages[0].lines[0]
        assertEquals(12, first.endChar)
        assertEquals(100.0, first.xs[11], 1e-6)
        val last = frame.pages[0].lines[1]
        assertEquals(0.0, last.xs[0], 1e-9)
        assertEquals(14.4, last.xs[2] - last.xs[0], 1e-9)
    }

    @Test
    fun centerAndRightAlignOffsetTheLine() {
        val center = flowOf("aaaa").apply { paragraphs[0].align = ParaAlign.CENTER }
        assertEquals(35.6, layout.layout(center, pages(1), 150).pages[0].lines[0].xs[0], 1e-9)
        val right = flowOf("aaaa").apply { paragraphs[0].align = ParaAlign.RIGHT }
        assertEquals(71.2, layout.layout(right, pages(1), 150).pages[0].lines[0].xs[0], 1e-9)
    }

    @Test
    fun marginsInsetTheContentRect() {
        val flow = flowOf("x").apply { margins = FlowMargins(25.4, 25.4, 25.4, 25.4) }
        val frame = layout.layout(flow, listOf(PageBox(1240.0, 1754.0)), 150)
        val rect = frame.pages[0].contentRect
        assertEquals(150.0, rect.left, 1e-9)
        assertEquals(150.0, rect.top, 1e-9)
        assertEquals(1240.0 - 300.0, rect.w, 1e-9)
        assertEquals(150.0, frame.pages[0].lines[0].top, 1e-9)
    }

    @Test
    fun hitTestAndCaretRectAgree() {
        val flow = flowOf("hello world", "todo")
        flow.paragraphs[1].list = ListKind.CHECK
        val frame = layout.layout(flow, pages(2), 150)

        val hit = frame.hitTest(0, Pt(22.0, 5.0))
        assertTrue(hit is FlowHit.Caret)
        assertEquals(FlowPos(0, 3), (hit as FlowHit.Caret).pos)
        val (page, rect) = frame.caretRect(hit.pos)!!
        assertEquals(0, page)
        assertEquals(21.6, rect.left, 1e-9)
        assertEquals(0.0, rect.top, 1e-9)

        val checkboxLine = frame.pages[0].lines[1]
        val marker = checkboxLine.marker!!
        assertEquals(ListKind.CHECK, marker.kind)
        val boxHit = frame.hitTest(0, marker.rect.center)
        assertTrue(boxHit is FlowHit.Checkbox)
        assertEquals(1, (boxHit as FlowHit.Checkbox).paraIndex)

        assertTrue(frame.hitTest(1, Pt(50.0, 25.0)) is FlowHit.BeyondEnd)
        assertTrue(frame.hitTest(0, Pt(50.0, 49.0)) is FlowHit.BeyondEnd)
    }

    @Test
    fun emptyLineFillWalksTheIntermediatePages() {
        val flow = flowOf("x")
        val frame = layout.layout(flow, pages(3), 150)
        val slot = layout.defaultSlotHeight(flow)
        assertEquals(15.6, slot, 1e-9)

        val count = frame.emptyLinesToReach(2, 25.0, slot)
        assertEquals(7, count)

        FlowEditor(flow).appendEmptyLines(count)
        val after = layout.layout(flow, pages(3), 150)
        val (page, rect) = after.caretRect(FlowPos(7, 0))!!
        assertEquals(2, page)
        assertTrue(25.0 >= rect.top && 25.0 <= rect.bottom)
        assertEquals(0, after.extraPagesNeeded)

        assertEquals(0, frame.emptyLinesToReach(0, 5.0, slot))
    }

    @Test
    fun orderedListNumberingRunsAndResets() {
        val flow = flowOf("one", "two", "bullet", "restart")
        flow.paragraphs[0].list = ListKind.ORDERED
        flow.paragraphs[1].list = ListKind.ORDERED
        flow.paragraphs[2].list = ListKind.BULLET
        flow.paragraphs[3].list = ListKind.ORDERED
        val frame = layout.layout(flow, listOf(PageBox(400.0, 400.0)), 150)
        val markers = frame.pages[0].lines.map { it.marker!! }
        assertEquals(listOf(1, 2, 0, 1), markers.map { it.ordinal })
        assertEquals(FlowLayout.MARKER_GUTTER_PX, frame.pages[0].lines[0].xs[0], 1e-9)
    }

    @Test
    fun selectionRectsMarkTheParagraphBreak() {
        val flow = flowOf("ab", "cd")
        val frame = layout.layout(flow, pages(1), 150)
        val rects = frame.selectionRects(FlowRange(FlowPos(0, 1), FlowPos(1, 1)))
        assertEquals(2, rects.size)
        assertEquals(7.2 + FlowFrame.NEWLINE_TAIL, rects[0].second.w, 1e-9)
        assertEquals(7.2, rects[1].second.w, 1e-9)
    }
}
