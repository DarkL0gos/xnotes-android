package com.xnotes.desktop

import com.xnotes.canvas.ChromePalette
import com.xnotes.core.model.Rgba

/** The desktop canvas themes: the app's dark and light palettes (Palette.dark/light on Android). */
internal object DesktopPalette {
    val DARK: ChromePalette = ChromePalette.DARK

    val LIGHT: ChromePalette = object : ChromePalette {
        override val bg = Rgba(0xe8, 0xe8, 0xe8)
        override val paper = Rgba(0xff, 0xff, 0xff)
        override val paperBorder = Rgba(0xc4, 0xc4, 0xc4)
        override val accent = Rgba(0, 161, 82)
        override val textDim = Rgba(0x6f, 0x6f, 0x6f)
        override val panel = Rgba(0xf4, 0xf4, 0xf4)
        override val border = Rgba(0xd0, 0xd0, 0xd0)
        override val text = Rgba(0x1c, 0x1c, 0x1c)
        override val menuBg = Rgba(0xfb, 0xfb, 0xfb)
        override val isDark = false
    }
}
