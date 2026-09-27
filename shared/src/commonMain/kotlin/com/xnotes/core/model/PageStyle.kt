package com.xnotes.core.model

/**
 * A page background ruling. [NONE] is an **explicit** "off" — distinct from a null
 * [PageStyle.pattern], which means *inherit from the level below*. Serialized by [id].
 */
enum class PagePattern(val id: String) {
    NONE("none"),
    LINES("lines"),
    DOTS("dots"),
    GRID("grid");

    companion object {
        fun fromId(id: String?): PagePattern? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A per-field-inheritable page style override, held on both [Page] (current page) and [Document]
 * ("all pages" of this note). Each field is **independently** nullable: a null field inherits from
 * the next level down — page → document → global preference (page colour) or a built-in default
 * (pattern/colour/spacing). This per-field nullability is what gives the UI its tri-state controls
 * (Default / explicit value / — for the pattern — an explicit None). Immutable: callers replace it
 * with a [copy]; sharing the reference across a [Page.deepCopy] is safe.
 */
data class PageStyle(
    val pageColor: Rgba? = null,
    val pattern: PagePattern? = null,
    val patternColor: Rgba? = null,
    /** Pattern period in content pixels. */
    val spacing: Double? = null,
) {
    /** True when nothing is overridden — the codec writes no `style` object in this case. */
    val isEmpty: Boolean
        get() = pageColor == null && pattern == null && patternColor == null && spacing == null

    companion object {
        const val DEFAULT_SPACING = 64.0 // content px (~10.8 mm at 150 dpi)
        const val MIN_SPACING = 16.0
        const val MAX_SPACING = 200.0
        val DEFAULT_PATTERN_COLOR = Rgba(150, 150, 150, 64) // grey at ~25% opacity by default

        /** Fixed (non-configurable) ruling thickness / dot radius, in content pixels. */
        const val LINE_THICKNESS = 1.5
        const val DOT_RADIUS = 2.0
    }
}

// --- resolution: a page's own override -> its document's ("all pages") override -> a caller default ---
// Shared by the live canvas ([CanvasState]) and PDF export so both honour the same hierarchy, and so
// each resolves against the document actually being drawn/exported (not necessarily the open one).

/** Resolved paper colour, or null to fall back to the theme paper. [global] is the app-wide preference. */
fun Page.resolvedPageColor(doc: Document, global: Rgba?): Rgba? =
    style.pageColor ?: doc.style.pageColor ?: global

fun Page.resolvedPattern(doc: Document): PagePattern =
    style.pattern ?: doc.style.pattern ?: PagePattern.NONE

fun Page.resolvedPatternColor(doc: Document): Rgba =
    style.patternColor ?: doc.style.patternColor ?: PageStyle.DEFAULT_PATTERN_COLOR

fun Page.resolvedSpacing(doc: Document): Double =
    (style.spacing ?: doc.style.spacing ?: PageStyle.DEFAULT_SPACING)
        .coerceIn(PageStyle.MIN_SPACING, PageStyle.MAX_SPACING)
