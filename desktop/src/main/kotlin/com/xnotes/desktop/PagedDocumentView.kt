package com.xnotes.desktop

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.insets
import com.xnotes.core.model.paintPagePattern
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedPattern
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.FlowPainter
import com.xnotes.core.text.PageBox
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.math.ceil

/** Renders a page using the same model paint commands as Android. */
internal object PagePainter {
    fun paint(graphics: Graphics2D, document: Document, pageIndex: Int, frame: FlowFrame?, darkPaper: Boolean) {
        val page = document.pages[pageIndex]
        val inset = page.insets(document)
        val cover = Rect(-inset.left, -inset.top,
            page.width + inset.left + inset.right, page.height + inset.top + inset.bottom)
        val visible = graphics.clipBounds?.let {
            Rect(it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())
        } ?: cover
        DesktopRenderer(graphics.create() as Graphics2D).use { painter ->
            painter.clipRect(cover)
            painter.fillBackground(cover, page.resolvedPageColor(document,
                if (darkPaper) Rgba(22, 22, 22, 255) else Rgba(255, 255, 255, 255))!!)
            paintPagePattern(painter, page.resolvedPattern(document), page.resolvedPatternColor(document),
                page.resolvedSpacing(document), cover, visible)
            if (page.pdfPage != null && document.pdfFile != null) {
                // The PDF source will be painted by the next platform layer step.
                painter.fillRect(Rect(0.0, 0.0, page.width, 30.0), Rgba(220, 225, 235, 255))
                painter.drawTextRun("Фон PDF пока недоступен", 8.0, 21.0,
                    com.xnotes.core.pal.FontSpec(13.0), Rgba(60, 65, 75, 255))
            }
            for (item in page.items) if (item.paintBounds().intersects(visible)) item.paint(painter)
            if (frame != null) FlowPainter.paintPage(painter, frame, pageIndex,
                visible)
        }
    }
}

internal class PagedDocumentView : JPanel(), Scrollable {
    private var document: Document? = null
    private var flowFrame: FlowFrame? = null
    var zoom = 0.65
        private set
    var darkPaper = true
        private set
    private val pageTops = ArrayList<Int>()
    private val pad = 28
    private val gap = 24

    init { background = java.awt.Color(38, 41, 47) }

    fun show(document: Document?) {
        this.document = document
        relayoutFlow()
        refreshSize()
        repaint()
    }

    fun setDarkPaper(value: Boolean) {
        darkPaper = value
        relayoutFlow()
        repaint()
    }

    private fun relayoutFlow() {
        flowFrame = document?.takeIf { !it.flow.isEmpty }?.let { doc ->
            FlowLayout(DesktopTextMeasurer).apply {
                autoColor = { if (darkPaper) Rgba(236, 236, 236, 255) else Rgba(28, 28, 28, 255) }
            }.layout(doc.flow, doc.pages.map { PageBox(it.width, it.height) }, doc.dpi)
        }
    }

    fun setZoom(next: Double) {
        zoom = next.coerceIn(0.15, 2.5)
        refreshSize()
        repaint()
    }

    fun pageCount(): Int = document?.pages?.size ?: 0

    fun pageTop(index: Int): Int = pageTops.getOrElse(index) { 0 }

    private fun refreshSize() {
        pageTops.clear()
        var y = pad
        var width = 600
        for (page in document?.pages.orEmpty()) {
            val margins = page.insets(document!!)
            pageTops.add(y)
            width = maxOf(width, ceil((page.width + margins.left + margins.right) * zoom).toInt() + 2 * pad)
            y += ceil((page.height + margins.top + margins.bottom) * zoom).toInt() + gap
        }
        preferredSize = Dimension(width, maxOf(380, y + pad))
        revalidate()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val doc = document ?: return
        val g = graphics.create() as Graphics2D
        try {
            for ((index, page) in doc.pages.withIndex()) {
                val margins = page.insets(doc)
                val screenW = (page.width + margins.left + margins.right) * zoom
                val screenH = (page.height + margins.top + margins.bottom) * zoom
                val x = ((width - screenW) / 2.0).coerceAtLeast(pad.toDouble())
                val y = pageTops[index].toDouble()
                if (!g.clipBounds.intersects(Rectangle(x.toInt(), y.toInt(), ceil(screenW).toInt(), ceil(screenH).toInt()))) continue
                g.color = java.awt.Color(20, 22, 25)
                g.fillRect(x.toInt() + 4, y.toInt() + 5, ceil(screenW).toInt(), ceil(screenH).toInt())
                g.translate(x + margins.left * zoom, y + margins.top * zoom)
                g.scale(zoom, zoom)
                PagePainter.paint(g, doc, index, flowFrame, darkPaper)
                g.scale(1.0 / zoom, 1.0 / zoom)
                g.translate(-(x + margins.left * zoom), -(y + margins.top * zoom))
            }
        } finally { g.dispose() }
    }

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(900, 650)
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 32
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height - 48 else visibleRect.width - 48
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
