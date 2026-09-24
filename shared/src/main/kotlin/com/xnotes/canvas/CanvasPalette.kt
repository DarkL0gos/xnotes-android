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

/** The canvas colours plus the UI chrome ones the on-canvas tools (the ruler) are painted with. */
interface ChromePalette : CanvasPalette {
    val panel: Rgba
    val border: Rgba
    val text: Rgba
    val menuBg: Rgba
    val isDark: Boolean

    companion object {
        /** The app's default dark chrome, for hosts and tests that don't supply a theme. */
        val DARK: ChromePalette = object : ChromePalette {
            override val bg = Rgba(0x0a, 0x0a, 0x0a)
            override val paper = Rgba(0x16, 0x16, 0x16)
            override val paperBorder = Rgba(0x2c, 0x2c, 0x2c)
            override val accent = Rgba(0, 230, 118)
            override val textDim = Rgba(0x6f, 0x6f, 0x6f)
            override val panel = Rgba(0x0d, 0x0d, 0x0d)
            override val border = Rgba(0x24, 0x24, 0x24)
            override val text = Rgba(0xc8, 0xc8, 0xc8)
            override val menuBg = Rgba(0x11, 0x11, 0x11)
            override val isDark = true
        }
    }
}
