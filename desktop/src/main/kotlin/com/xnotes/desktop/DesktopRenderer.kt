package com.xnotes.desktop

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextFlags
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

internal fun Rgba.awt(): Color = Color(r, g, b, a)

internal class DesktopRasterSurface(
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double = 1.0,
) : RasterSurface {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val painterHandle = lazy {
        DesktopRenderer(image.createGraphics().apply { clipRect(0, 0, width, height) })
    }
    private val painter by painterHandle

    override fun fill(color: Rgba) {
        val g = image.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.color = color.awt()
            g.fillRect(0, 0, width, height)
        } finally { g.dispose() }
    }

    override fun renderer(): Renderer = painter
    override fun recycle() {
        if (painterHandle.isInitialized()) painter.close()
        image.flush()
    }
}

internal object DesktopSurfaceFactory : SurfaceFactory {
    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
        DesktopRasterSurface(widthPx, heightPx, devicePixelRatio)
}

/** Java2D implementation of the shared immediate-mode painter. */
internal class DesktopRenderer(root: Graphics2D) : Renderer, AutoCloseable {
    private data class Frame(val parent: Graphics2D, val layer: BufferedImage? = null,
                             val x: Int = 0, val y: Int = 0, val alpha: Float = 1f,
                             val blend: BlendMode = BlendMode.SRC_OVER)
    private val stack = ArrayDeque<Frame>()
    private var g: Graphics2D = root.apply { configure(this) }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    override fun save() {
        stack.addLast(Frame(g))
        g = g.create() as Graphics2D
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) =
        saveLayerBlended(bounds, alpha, BlendMode.SRC_OVER)

    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) {
        val device = g.transform.createTransformedShape(
            Rectangle2D.Double(bounds.x, bounds.y, bounds.w, bounds.h)
        ).bounds
        // A clipped-away layer still needs a matching restore. A one-pixel transparent
        // buffer keeps that pairing simple without allocating a whole page.
        val width = device.width.coerceIn(1, 8192)
        val height = device.height.coerceIn(1, 8192)
        val layer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val child = layer.createGraphics()
        configure(child)
        child.transform = AffineTransform.getTranslateInstance(-device.x.toDouble(), -device.y.toDouble()).apply {
            concatenate(g.transform)
        }
        child.clip(Rectangle2D.Double(bounds.x, bounds.y, bounds.w, bounds.h))
        stack.addLast(Frame(g, layer, device.x, device.y, alpha.coerceIn(0.0, 1.0).toFloat(), blend))
        g = child
    }

    override fun restore() {
        val frame = stack.removeLast()
        g.dispose()
        g = frame.parent
        val layer = frame.layer ?: return
        val target = g.create() as Graphics2D
        try {
            target.transform = AffineTransform()
            target.composite = if (frame.blend == BlendMode.SRC_OVER)
                AlphaComposite.SrcOver.derive(frame.alpha)
            else BlendComposite(frame.blend, frame.alpha)
            target.drawImage(layer, frame.x, frame.y, null)
        } finally {
            target.dispose()
            layer.flush()
        }
    }

    override fun translate(dx: Double, dy: Double) = g.translate(dx, dy)
    override fun scale(sx: Double, sy: Double) = g.scale(sx, sy)
    override fun rotate(degrees: Double) = g.rotate(Math.toRadians(degrees))
    override fun clipRect(rect: Rect) = g.clip(Rectangle2D.Double(rect.x, rect.y, rect.w, rect.h))

    override fun clear() {
        val before = g.composite
        g.composite = AlphaComposite.Clear
        g.fill(g.clip ?: Rectangle2D.Double(0.0, 0.0, 1.0, 1.0))
        g.composite = before
    }

    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)
    override fun fillRect(rect: Rect, color: Rgba) {
        g.color = color.awt()
        g.fill(Rectangle2D.Double(rect.x, rect.y, rect.w, rect.h))
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        g.color = color.awt()
        g.fill(path(points, true, rule))
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) {
        if (radius <= 0.0) return
        g.color = color.awt()
        g.fill(Ellipse2D.Double(center.x - radius, center.y - radius, 2 * radius, 2 * radius))
    }

    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        if (rx <= 0.0 || ry <= 0.0) return
        g.color = color.awt()
        g.fill(Ellipse2D.Double(center.x - rx, center.y - ry, 2 * rx, 2 * ry))
    }

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        val shape = Path2D.Double(Path2D.WIND_NON_ZERO)
        for (i in from until from + count - 1) {
            val x0 = centers[2 * i].toDouble(); val y0 = centers[2 * i + 1].toDouble()
            val x1 = centers[2 * i + 2].toDouble(); val y1 = centers[2 * i + 3].toDouble()
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 1e-9) continue
            val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
            val r0 = radii[i].toDouble(); val r1 = radii[i + 1].toDouble()
            val ax = x0 + nx * r0; val ay = y0 + ny * r0
            val bx = x1 + nx * r1; val by = y1 + ny * r1
            val cx = x1 - nx * r1; val cy = y1 - ny * r1
            val dx = x0 - nx * r0; val dy = y0 - ny * r0
            if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0) {
                shape.moveTo(ax, ay); shape.lineTo(bx, by); shape.lineTo(cx, cy); shape.lineTo(dx, dy)
            } else {
                shape.moveTo(dx, dy); shape.lineTo(cx, cy); shape.lineTo(bx, by); shape.lineTo(ax, ay)
            }
            shape.closePath()
        }
        for (i in from until from + count) {
            val radius = radii[i].toDouble()
            if (radius > 0.0) shape.append(Ellipse2D.Double(
                centers[2 * i] - radius, centers[2 * i + 1] - radius, 2 * radius, 2 * radius
            ), false)
        }
        g.color = color.awt()
        g.fill(shape)
    }

    private fun pen(pen: Pen) {
        g.color = pen.color.awt()
        val transform = g.transform
        val scale = max(1e-6, (hypot(transform.scaleX, transform.shearY) +
            hypot(transform.shearX, transform.scaleY)) / 2.0)
        val factor = if (pen.cosmetic) 1.0 / scale else 1.0
        val dash = if (pen.dashed) floatArrayOf(
            (pen.dashOn * factor).coerceAtLeast(0.1).toFloat(),
            (pen.dashGap * factor).coerceAtLeast(0.1).toFloat()
        ) else null
        g.stroke = BasicStroke((pen.width * factor).coerceAtLeast(0.01).toFloat(),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash,
            (pen.dashPhase * factor).toFloat())
    }

    override fun strokeRect(rect: Rect, pen: Pen) {
        pen(pen); g.draw(Rectangle2D.Double(rect.x, rect.y, rect.w, rect.h))
    }
    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        pen(pen); g.draw(path(points, false, FillRule.NONZERO))
    }
    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        pen(pen); g.draw(path(points, true, FillRule.NONZERO))
    }
    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        pen(pen); g.draw(Ellipse2D.Double(center.x - rx, center.y - ry, 2 * rx, 2 * ry))
    }

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        val image = (raster as? DesktopRasterSurface)?.image ?: return
        val s = src ?: Rect(0.0, 0.0, image.width.toDouble(), image.height.toDouble())
        g.drawImage(image, dest.left.toInt(), dest.top.toInt(), ceil(dest.right).toInt(), ceil(dest.bottom).toInt(),
            s.left.toInt(), s.top.toInt(), ceil(s.right).toInt(), ceil(s.bottom).toInt(), null)
    }

    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double) {
        if (dest.w <= 0.0 || dest.h <= 0.0) return
        val transform = g.transform
        val scale = max(1.0, max(hypot(transform.scaleX, transform.shearY),
            hypot(transform.shearX, transform.scaleY)))
        val turned = ((orientation % 180) + 180) % 180 == 90
        val width = (if (turned) dest.h else dest.w) * scale
        val height = (if (turned) dest.w else dest.h) * scale
        val bitmap = DesktopImageCodec.decode(image.file, ceil(width).toInt(), ceil(height).toInt()) ?: return
        val original = g.transform
        try {
            g.translate(dest.centerX, dest.centerY)
            g.rotate(Math.toRadians(orientation.toDouble()) + angle)
            val w = if (turned) dest.h else dest.w
            val h = if (turned) dest.w else dest.h
            g.drawImage(bitmap, AffineTransform.getTranslateInstance(-w / 2, -h / 2).apply {
                scale(w / bitmap.width, h / bitmap.height)
            }, null)
        } finally { g.transform = original }
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
        if (text.isEmpty()) return
        val previous = g.clip
        g.clip(Rectangle2D.Double(rect.x, rect.y, rect.w, rect.h))
        try {
            val lineHeight = DesktopTextMeasurer.lineHeight(font)
            var y = rect.y + DesktopTextMeasurer.metrics(font).ascent
            for (line in DesktopTextLayout.lines(text, font, rect.w, flags.wordWrap)) {
                drawTextRun(line, rect.x, y, font, color)
                y += lineHeight
            }
        } finally { g.clip = previous }
    }

    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        if (text.isEmpty()) return
        g.color = color.awt()
        g.font = DesktopFonts.resolve(font)
        g.drawString(text, x.toFloat(), baseline.toFloat())
    }

    private fun path(points: List<Pt>, close: Boolean, rule: FillRule): Path2D.Double =
        Path2D.Double(if (rule == FillRule.EVEN_ODD) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO).apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            if (close) closePath()
        }

    override fun close() {
        while (stack.isNotEmpty()) restore()
        g.dispose()
    }
}

/** Separable blend for highlighter layers over both opaque and transparent paper. */
private class BlendComposite(private val mode: BlendMode, private val alpha: Float) : Composite {
    override fun createContext(srcColorModel: ColorModel, dstColorModel: ColorModel,
                               hints: RenderingHints?): CompositeContext = object : CompositeContext {
        override fun dispose() = Unit
        override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
            val width = minOf(src.width, dstIn.width, dstOut.width)
            val height = minOf(src.height, dstIn.height, dstOut.height)
            for (y in 0 until height) for (x in 0 until width) {
                val s = srcColorModel.getRGB(src.getDataElements(src.minX + x, src.minY + y, null))
                val d = dstColorModel.getRGB(dstIn.getDataElements(dstIn.minX + x, dstIn.minY + y, null))
                val sa = ((s ushr 24) and 255) / 255.0 * alpha
                val da = ((d ushr 24) and 255) / 255.0
                val outA = sa + da * (1 - sa)
                var out = (outA * 255 + 0.5).toInt().coerceIn(0, 255) shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val sc = ((s ushr shift) and 255) / 255.0
                    val dc = ((d ushr shift) and 255) / 255.0
                    val mixed = when (mode) {
                        BlendMode.MULTIPLY -> sc * dc
                        BlendMode.SCREEN -> 1 - (1 - sc) * (1 - dc)
                        BlendMode.SRC_OVER -> sc
                    }
                    val value = if (outA == 0.0) 0.0 else
                        (sa * (1 - da) * sc + da * (1 - sa) * dc + sa * da * mixed) / outA
                    out = out or ((value * 255 + 0.5).toInt().coerceIn(0, 255) shl shift)
                }
                dstOut.setDataElements(dstOut.minX + x, dstOut.minY + y,
                    dstColorModel.getDataElements(out, null))
            }
        }
    }
}
