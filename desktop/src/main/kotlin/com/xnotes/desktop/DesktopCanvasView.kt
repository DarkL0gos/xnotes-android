package com.xnotes.desktop

import com.xnotes.canvas.CanvasFramePainter
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.ChromePalette
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.PageBox
import com.xnotes.core.tools.Tool
import com.xnotes.input.PointerEvent
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The editable paged canvas: the shared [CanvasState] and [InteractionController] behind a Swing
 * component. Frames are painted by the shared [CanvasFramePainter] into an offscreen image at device
 * resolution (sharp on HiDPI, and the highlighter's MULTIPLY blend needs readable pixels), then
 * blitted. The mouse reaches the controller as [SimplePointerEvent]s, so the controller cannot tell
 * it from Android input; Swing reports no pen pressure, so ink draws at full width.
 */
internal class DesktopCanvasView : JComponent() {
    private val scheduler = SwingUiScheduler()
    private val history = History()
    private var palette: ChromePalette = DesktopPalette.DARK

    val state = CanvasState(Document.blank(1), DesktopSurfaceFactory, palette)
    private val controller = InteractionController(
        state,
        history,
        DesktopTextMeasurer,
        requestRender = { repaint() },
        scheduler = scheduler,
        chromePalette = { palette },
        onContentChanged = { contentChanged() },
        onViewChanged = { viewChanged() },
    )

    /** The document was edited (not just viewed): the host marks it unsaved and checkpoints it. */
    var onEdited: () -> Unit = {}

    /** Zoom, scroll or page changed. */
    var onViewChanged: () -> Unit = {}

    /** Undo availability may have changed. */
    var onHistoryChanged: () -> Unit = {}

    private val cacheThread = Executors.newSingleThreadExecutor { r -> Thread(r, "xnotes-cache").apply { isDaemon = true } }
    private val sharpSettle = Timer(SHARP_SETTLE_MS) { state.requestSharpViewport() }.apply { isRepeats = false }
    private var buffer: BufferedImage? = null
    private var flowFrame: FlowFrame? = null

    /** Device pixels per logical pixel; the canvas works in device pixels throughout. */
    private var deviceScale = 1.0

    /** The last viewport position of a middle-button drag, which pans directly. */
    private var panFrom: Pt? = null

    init {
        isOpaque = true
        isFocusable = true
        state.runAsync = { work -> cacheThread.execute(work) }
        state.postToMain = { work -> SwingUtilities.invokeLater(work) }
        state.onCacheReady = { repaint() }
        state.paintFlow = { page, renderer, region ->
            val frame = flowFrame
            val index = state.document.pages.indexOfFirst { it === page }
            if (frame != null && index >= 0) FlowPainter.paintPage(renderer, frame, index, region)
        }
        controller.setTool(Tool.PEN)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = layoutViewport()
        })
        val mouse = CanvasMouse()
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    val document: Document get() = state.document
    val tool: Tool get() = controller.tool
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val zoomPercent: Int get() = (state.zoom * 100).toInt()
    val pageCount: Int get() = state.document.pages.size
    fun currentPage(): Int = state.currentPageIndex()

    /** Show [doc], dropping everything that belonged to the previous one. */
    fun show(doc: Document) {
        controller.commitTextEdit()
        controller.clearSelection()
        controller.resetGestureState()
        state.document = doc
        history.clear()
        relayoutFlow()
        state.invalidateAllCaches()
        state.didInitialFit = false
        layoutViewport()
        onHistoryChanged()
        repaint()
    }

    fun setTool(tool: Tool) {
        controller.setTool(tool)
        cursor = java.awt.Cursor.getPredefinedCursor(
            if (tool == Tool.PAN) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.CROSSHAIR_CURSOR,
        )
    }

    var inkColor: Rgba
        get() = controller.inkColor
        set(value) {
            controller.inkColor = value
        }

    fun setDarkPaper(dark: Boolean) {
        palette = if (dark) DesktopPalette.DARK else DesktopPalette.LIGHT
        state.palette = palette
        relayoutFlow() // auto-coloured text follows the paper
        state.invalidateAllCaches()
        repaint()
    }

    fun zoomStep(zoomIn: Boolean) {
        state.zoomByStep(zoomIn)
        viewChanged()
    }

    fun fitWidth() {
        state.fitWidth()
        viewChanged()
    }

    fun goToPage(index: Int) {
        state.goToPage(index)
        viewChanged()
    }

    fun escape() = controller.escape()
    fun deleteSelection() = controller.deleteSelection()
    fun selectAll() = controller.selectAll()

    fun undo() {
        val command = history.nextUndo ?: return
        applyHistory(command) { history.undo() }
    }

    fun redo() {
        val command = history.nextRedo ?: return
        applyHistory(command) { history.redo() }
    }

    /** The view to persist in the session. */
    fun viewState(): Map<String, Double> =
        mapOf(VIEW_ZOOM to state.zoom, VIEW_SCROLL_X to state.scrollX, VIEW_SCROLL_Y to state.scrollY)

    fun restoreView(view: Map<String, Double>) {
        val zoom = view[VIEW_ZOOM] ?: return
        state.didInitialFit = true // the saved view replaces the initial fit
        state.zoom = zoom.coerceIn(state.minZoom, state.maxZoom)
        state.scrollX = view[VIEW_SCROLL_X] ?: 0.0
        state.scrollY = view[VIEW_SCROLL_Y] ?: 0.0
        state.clampScroll()
        viewChanged()
    }

    fun dispose() {
        sharpSettle.stop()
        scheduler.shutdown()
        cacheThread.shutdownNow()
    }

    /**
     * Undo/redo, then repair only what the command touched: its regions before and after, or every
     * page when it can't say. Page adds/removes also relayout. Mirrors the Android editor.
     */
    private fun applyHistory(command: Command, step: () -> Unit) {
        val pagesBefore = state.document.pages.size
        val before = touchedRegions(command)
        step()
        val after = touchedRegions(command)
        controller.clearSelection()
        if (state.document.pages.size != pagesBefore) state.relayout()
        relayoutFlow()
        if (before == null || after == null) state.refreshAllInk() else state.repairInkRegions(before + after)
        state.document.dirty = true
        state.clampScroll()
        onHistoryChanged()
        onEdited()
        repaint()
    }

    private fun touchedRegions(command: Command): List<Pair<Page, Rect>>? {
        var index: HashMap<CanvasItem, Page>? = null
        val locate: (CanvasItem) -> Page? = { item ->
            val built = index ?: HashMap<CanvasItem, Page>().also { map ->
                for (page in state.document.pages) for (it in page.items) map[it] = page
                index = map
            }
            built[item]
        }
        return command.touched(locate)?.map { (page, item) -> page to item.paintBounds() }
    }

    private fun contentChanged() {
        onHistoryChanged()
        if (state.document.dirty) onEdited()
    }

    private fun viewChanged() {
        onViewChanged()
        repaint()
    }

    private fun relayoutFlow() {
        val doc = state.document
        val dark = palette.isDark
        flowFrame = doc.takeIf { !it.flow.isEmpty }?.let {
            FlowLayout(DesktopTextMeasurer).apply {
                autoColor = { if (dark) Rgba(236, 236, 236, 255) else Rgba(28, 28, 28, 255) }
            }.layout(it.flow, it.pages.map { page -> PageBox(page.width, page.height) }, it.dpi)
        }
    }

    private fun layoutViewport() {
        deviceScale = graphicsConfiguration?.defaultTransform?.scaleX?.coerceAtLeast(1.0) ?: 1.0
        val w = ceil(width * deviceScale).toInt()
        val h = ceil(height * deviceScale).toInt()
        if (w <= 0 || h <= 0) return
        val resized = w != state.viewportW
        state.devicePxPerDp = deviceScale
        state.viewportW = w
        state.viewportH = h
        state.relayout()
        if (!state.didInitialFit) state.establishInitialView() else if (resized) state.reflowFitWidthForResize()
        state.clampScroll()
        viewChanged()
    }

    override fun paintComponent(g: Graphics) {
        val w = state.viewportW
        val h = state.viewportH
        if (w <= 0 || h <= 0) return
        val image = buffer?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { buffer = it }
        val frame = DesktopRenderer(image.createGraphics()).use { r ->
            CanvasFramePainter.paint(r, state).also { controller.drawOverlay(r) }
        }
        CanvasFramePainter.finish(state, frame)
        if (frame.sharpSettleNeeded) sharpSettle.restart() else sharpSettle.stop()
        (g as Graphics2D).drawImage(image, 0, 0, width, height, null)
    }

    /** Mouse → controller. The left button acts as a pen with the armed tool; the middle button pans. */
    private inner class CanvasMouse : MouseAdapter() {
        private fun at(e: MouseEvent) = Pt(e.x * deviceScale, e.y * deviceScale)

        private fun event(action: Int, e: MouseEvent) = at(e).let { p ->
            SimplePointerEvent(
                action,
                listOf(PointerSample(0, p.x.toFloat(), p.y.toFloat(), 1f, PointerEvent.TOOL_TYPE_MOUSE)),
                System.nanoTime() / 1_000_000L,
            )
        }

        override fun mousePressed(e: MouseEvent) {
            requestFocusInWindow()
            when {
                SwingUtilities.isMiddleMouseButton(e) -> panFrom = at(e)
                SwingUtilities.isLeftMouseButton(e) -> controller.onTouch(event(PointerEvent.ACTION_DOWN, e))
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            val from = panFrom
            if (from != null) {
                val to = at(e)
                state.scrollBy(from.x - to.x, from.y - to.y)
                panFrom = to
                viewChanged()
            } else if (SwingUtilities.isLeftMouseButton(e)) {
                controller.onTouch(event(PointerEvent.ACTION_MOVE, e))
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            when {
                SwingUtilities.isMiddleMouseButton(e) -> panFrom = null
                SwingUtilities.isLeftMouseButton(e) -> controller.onTouch(event(PointerEvent.ACTION_UP, e))
            }
        }

        override fun mouseMoved(e: MouseEvent) {
            controller.onHover(event(PointerEvent.ACTION_HOVER_MOVE, e))
        }

        override fun mouseExited(e: MouseEvent) {
            controller.onHover(event(PointerEvent.ACTION_HOVER_EXIT, e))
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val rotation = e.preciseWheelRotation
            if (e.modifiersEx and InputEvent.CTRL_DOWN_MASK != 0) {
                state.setZoomAnchored(at(e), state.zoom * ZOOM_PER_NOTCH.pow(-rotation))
            } else {
                val step = rotation * WHEEL_STEP_PX * deviceScale
                if (e.isShiftDown) state.scrollBy(step, 0.0) else state.scrollBy(0.0, step)
            }
            viewChanged()
        }
    }

    companion object {
        const val VIEW_ZOOM = "zoom"
        const val VIEW_SCROLL_X = "scrollX"
        const val VIEW_SCROLL_Y = "scrollY"
        private const val SHARP_SETTLE_MS = 90
        private const val WHEEL_STEP_PX = 60.0
        private const val ZOOM_PER_NOTCH = 1.1
    }
}
