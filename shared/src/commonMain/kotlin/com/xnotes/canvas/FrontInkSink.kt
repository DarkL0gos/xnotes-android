package com.xnotes.canvas

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke

/**
 * A low-latency surface wet ink is drawn to while the pen is down (Android's front buffer). The
 * controller feeds it the live stroke and, when the stroke is filed, lets it keep showing the
 * pixels until the regular canvas has caught up. Hosts without one leave it unset.
 */
interface FrontInkSink {
    /** True while the surface is drawing the live stroke, so the canvas overlay must not. */
    val live: Boolean

    /** Whether [item] is still being shown by this surface (the canvas skips it meanwhile). */
    fun holding(item: CanvasItem): Boolean

    /** The live stroke grew (or was replaced / cleared when [stroke] is null). */
    fun wet(stroke: Stroke?, pageIndex: Int?)

    /** Keep showing the filed [item] on [page] until the canvas has it; false when not live. */
    fun hold(item: CanvasItem, page: Page): Boolean

    /** [hold] for disappearing ink, which the caller's overlay paints once the surface lets go. */
    fun holdFading(stroke: Stroke): Boolean

    /** Drop the live stroke's pixels (the stroke was aborted). */
    fun abandon()
}
