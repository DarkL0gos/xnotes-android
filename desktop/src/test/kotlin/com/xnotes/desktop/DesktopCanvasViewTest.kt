package com.xnotes.desktop

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * The desktop editor end to end, headless: synthetic Swing mouse events go in, and the document,
 * the undo stack and the painted pixels are checked. Everything runs on the event thread, as it
 * would in the app.
 */
class DesktopCanvasViewTest {
    private lateinit var view: DesktopCanvasView

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun <T> onEdtGet(block: () -> T): T {
        var out: T? = null
        SwingUtilities.invokeAndWait { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun setUp(doc: Document = Document.blank(count = 2)) = onEdt {
        view = DesktopCanvasView()
        view.setSize(800, 900)
        view.dispatchEvent(ComponentEvent(view, ComponentEvent.COMPONENT_RESIZED))
        view.show(doc)
        view.setDarkPaper(false) // white paper, so dark ink is easy to find in the pixels
    }

    @After fun tearDown() = onEdt { view.dispose() }

    private fun mouse(id: Int, at: Pt, modifiers: Int = InputEvent.BUTTON1_DOWN_MASK) {
        val button = if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
        view.dispatchEvent(MouseEvent(view, id, System.currentTimeMillis(), modifiers,
            at.x.toInt(), at.y.toInt(), 1, false, button))
    }

    /** Drag with the left button through [points] (viewport pixels). */
    private fun drag(vararg points: Pt) = onEdt {
        mouse(MouseEvent.MOUSE_PRESSED, points.first())
        for (p in points.drop(1)) mouse(MouseEvent.MOUSE_DRAGGED, p)
        mouse(MouseEvent.MOUSE_RELEASED, points.last(), 0)
    }

    /** A viewport point on the first page, [fx]/[fy] of the way across its visible part. */
    private fun onFirstPage(fx: Double, fy: Double): Pt = onEdtGet {
        val rect = view.state.pageRects[0]
        view.state.contentToViewport(Pt(rect.left + rect.w * fx, rect.top + rect.h * fy))
    }

    private fun items() = onEdtGet { view.document.pages[0].items.toList() }

    /** Paint until [check] holds on the frame: page caches build on a worker thread. */
    private fun paintedUntil(check: (BufferedImage) -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val image = BufferedImage(800, 900, BufferedImage.TYPE_INT_ARGB)
            onEdt {
                val g = image.createGraphics()
                try { view.paint(g) } finally { g.dispose() }
            }
            if (check(image)) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun isInk(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return r < 80 && g < 80 && b < 80
    }

    @Test fun dragWithThePenFilesAStrokeAndPaintsIt() {
        setUp()
        onEdt {
            view.setTool(Tool.PEN)
            view.inkColor = Rgba(0, 0, 0)
        }
        var edits = 0
        onEdt { view.onEdited = { edits++ } }
        val a = onFirstPage(0.2, 0.2)
        val b = onFirstPage(0.6, 0.25)
        val mid = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
        val inkAtMid = { image: BufferedImage -> (-3..3).any { dy -> isInk(image.getRGB(mid.x.toInt(), mid.y.toInt() + dy)) } }
        assertTrue("blank paper first", paintedUntil { !inkAtMid(it) })
        drag(a, mid, b)

        val stroke = items().single() as Stroke
        assertEquals(Tool.PEN, stroke.tool)
        assertTrue(onEdtGet { view.document.dirty })
        assertTrue(edits > 0)
        assertTrue(onEdtGet { view.canUndo })

        assertTrue("the stroke should be visible on screen", paintedUntil(inkAtMid))
    }

    @Test fun undoAndRedoRestoreTheStroke() {
        setUp()
        onEdt { view.setTool(Tool.PEN) }
        drag(onFirstPage(0.2, 0.5), onFirstPage(0.5, 0.5), onFirstPage(0.8, 0.5))
        assertEquals(1, items().size)

        onEdt { view.undo() }
        assertTrue(items().isEmpty())
        assertTrue(onEdtGet { view.canRedo })

        onEdt { view.redo() }
        assertEquals(1, items().size)
    }

    @Test fun theEraserRemovesAStrokeItCrosses() {
        setUp()
        onEdt { view.setTool(Tool.PEN) }
        drag(onFirstPage(0.2, 0.4), onFirstPage(0.5, 0.4), onFirstPage(0.8, 0.4))
        assertEquals(1, items().size)

        onEdt { view.setTool(Tool.ERASER) }
        drag(onFirstPage(0.5, 0.3), onFirstPage(0.5, 0.4), onFirstPage(0.5, 0.5))
        assertTrue("the eraser should take the stroke out", items().isEmpty())
    }

    @Test fun thePanToolScrollsWithoutDrawing() {
        setUp(Document.blank(count = 4))
        onEdt { view.setTool(Tool.PAN) }
        val before = onEdtGet { view.state.scrollY }
        drag(Pt(400.0, 700.0), Pt(400.0, 500.0), Pt(400.0, 300.0))
        assertTrue(items().isEmpty())
        assertFalse(before == onEdtGet { view.state.scrollY })
    }

    @Test fun viewStateRoundTrips() {
        setUp(Document.blank(count = 4))
        onEdt { view.restoreView(mapOf("zoom" to 0.8, "scrollX" to 0.0, "scrollY" to 500.0)) }
        val saved = onEdtGet { view.viewState() }
        assertEquals(0.8, saved["zoom"]!!, 1e-9)
        assertEquals(500.0, saved["scrollY"]!!, 1e-9)
    }
}
