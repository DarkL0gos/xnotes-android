package com.xnotes.canvas

import com.xnotes.core.model.Rgba

/**
 * The colours the canvas itself paints with: the gap around pages, the default paper, page
 * borders and the scrollbar/selection accents. The host's full theme implements this, so the
 * canvas core never depends on a UI toolkit's palette.
 */
interface CanvasPalette {
    val bg: Rgba
    val paper: Rgba
    val paperBorder: Rgba
    val accent: Rgba
    val textDim: Rgba
}
