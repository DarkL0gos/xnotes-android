package com.xnotes.canvas

import com.xnotes.core.model.Rgba

/** The app's dark palette, reduced to what the canvas paints with. */
data class TestPalette(
    override val bg: Rgba = Rgba(0x0a, 0x0a, 0x0a),
    override val paper: Rgba = Rgba(0x16, 0x16, 0x16),
    override val paperBorder: Rgba = Rgba(0x2c, 0x2c, 0x2c),
    override val accent: Rgba = Rgba(0, 230, 118),
    override val textDim: Rgba = Rgba(0x6f, 0x6f, 0x6f),
) : CanvasPalette
