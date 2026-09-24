package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.input.PointerEvent
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paged controller driven end to end through host-neutral [PointerEvent]s, as a desktop host would. */
class PointerInputTest {
    private val scheduler = FakeUiScheduler()
    private val history = History()

    private fun state(vararg items: Stroke): CanvasState =
        // Taller than the viewport, so there is somewhere to scroll.
        CanvasState(Document(mutableListOf(Page(400.0, 800.0, items.toMutableList()), Page(400.0, 800.0), Page(400.0, 800.0))),
            FakeSurfaceFactory(), TestPalette()).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }

    private fun controller(st: CanvasState) =
        InteractionController(st, history, FakeTextMeasurer(), requestRender = {}, scheduler = scheduler)

    /** Viewport point of a page-space point on page 0. */
    private fun CanvasState.viewportOf(x: Double, y: Double): Pt = contentToViewport(fromPageSpace(0, Pt(x, y)))

    private fun event(action: Int, at: Pt, time: Long, tool: Int, pressure: Float = 0.6f,
                      history: List<Pair<Long, Pt>> = emptyList()) =
        SimplePointerEvent(
            action,
            listOf(PointerSample(0, at.x.toFloat(), at.y.toFloat(), pressure, tool)),
            time,
            history = history.map { (t, p) -> t to listOf(PointerSample(0, p.x.toFloat(), p.y.toFloat(), pressure, tool)) },
        )

    @Test fun aPenGestureFilesAStrokeAsOneUndoStep() {
        val st = state()
        val ctrl = controller(st)
        ctrl.setTool(Tool.PEN)
        val stylus = PointerEvent.TOOL_TYPE_STYLUS

        ctrl.onTouch(event(PointerEvent.ACTION_DOWN, st.viewportOf(50.0, 50.0), 0, stylus))
        // One MOVE carrying coalesced samples, as a tablet delivers them between frames.
        ctrl.onTouch(event(PointerEvent.ACTION_MOVE, st.viewportOf(120.0, 90.0), 30, stylus,
            history = listOf(10L to st.viewportOf(70.0, 60.0), 20L to st.viewportOf(95.0, 75.0))))
        ctrl.onTouch(event(PointerEvent.ACTION_UP, st.viewportOf(120.0, 90.0), 40, stylus))

        val items = st.document.pages[0].items
        assertEquals(1, items.size)
        val stroke = items.single() as Stroke
        assertEquals(Tool.PEN, stroke.tool)
        assertTrue("coalesced samples must reach the stroke", stroke.samples.size >= 4)
        assertTrue(st.document.dirty)
        assertTrue(history.canUndo)
    }

    @Test fun aFingerPansInsteadOfDrawing() {
        val st = state()
        val ctrl = controller(st)
        ctrl.setTool(Tool.PEN)
        val finger = PointerEvent.TOOL_TYPE_FINGER
        val before = st.scrollY

        ctrl.onTouch(event(PointerEvent.ACTION_DOWN, Pt(400.0, 600.0), 0, finger))
        ctrl.onTouch(event(PointerEvent.ACTION_MOVE, Pt(400.0, 450.0), 16, finger))
        ctrl.onTouch(event(PointerEvent.ACTION_UP, Pt(400.0, 450.0), 32, finger))

        assertTrue(st.document.pages[0].items.isEmpty())
        assertNotEquals(before, st.scrollY)
    }

    @Test fun aHeldFingerGrabsTheItemOnlyAfterTheLongPressTimeout() {
        val ink = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(100.0, 100.0, 1.0), Sample(160.0, 100.0, 1.0)))
        val st = state(ink)
        val ctrl = controller(st)
        val finger = PointerEvent.TOOL_TYPE_FINGER

        ctrl.onTouch(event(PointerEvent.ACTION_DOWN, st.viewportOf(130.0, 100.0), 0, finger))
        scheduler.advance(InteractionController.LONG_PRESS_MS - 1)
        assertFalse(ctrl.hasSelection)
        scheduler.advance(1)
        assertTrue(ctrl.hasSelection)
    }
}
