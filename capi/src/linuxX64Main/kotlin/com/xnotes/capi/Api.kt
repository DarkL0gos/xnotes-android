@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.xnotes.capi

import com.xnotes.canvas.CanvasFramePainter
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.ChromePalette
import com.xnotes.canvas.InteractionController
import com.xnotes.capi.c.xn_host
import com.xnotes.capi.c.xn_palette
import com.xnotes.capi.c.xn_pointer
import com.xnotes.capi.c.xn_pointer_event
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.platform.Runnable
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asOutputStream
import com.xnotes.core.platform.fileOf
import com.xnotes.core.tools.Tool
import com.xnotes.editor.EditorNotice
import com.xnotes.editor.NoteEditor
import com.xnotes.editor.NoteEditorHost
import com.xnotes.format.DocumentCodec
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.use
import platform.posix.free
import platform.posix.memcpy
import platform.posix.strdup
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/*
 * The exported C functions of xnotes.h. Handles are StableRefs to the Kotlin objects below. No
 * exception may cross into C (that would abort the process): every entry point catches and reports.
 */

/** A copy of the host's struct, owned here, so the host need not keep its own alive. */
private fun copyHost(host: CPointer<xn_host>): xn_host {
    val copy = nativeHeap.alloc<xn_host>()
    memcpy(copy.ptr, host, kotlinx.cinterop.sizeOf<xn_host>().toULong())
    return copy
}

/** Measures nothing: for reading or writing a note when the host gave no measurer. */
private object NoTextMeasurer : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags) =
        com.xnotes.core.geometry.Rect(0.0, 0.0, wrapWidth, font.pointSize)
    override fun lineHeight(font: FontSpec) = font.pointSize
    override fun metrics(font: FontSpec) = LineMetrics(font.pointSize * 0.8, font.pointSize * 0.2)
    override fun advances(text: String, font: FontSpec) = DoubleArray(text.length) { font.pointSize * 0.5 }
}

private object NoImageCodec : ImageCodec {
    override fun probeFile(path: String): ImageSize? = null
}

private fun codecFor(host: CPointer<xn_host>?): DocumentCodec {
    if (host == null) return DocumentCodec(NoImageCodec, NoTextMeasurer)
    val h = host.pointed
    return DocumentCodec(CImageCodec(h), CTextMeasurer(h))
}

private fun reportError(error: CPointer<CPointerVar<ByteVar>>?, t: Throwable) {
    error?.set(0, strdup(t.message ?: t::class.simpleName ?: "error"))
}

internal class CNote(val document: Document)

private fun COpaquePointer.note(): CNote = asStableRef<CNote>().get()

private fun COpaquePointer.editor(): CEditor = asStableRef<CEditor>().get()

@CName("xn_api_version")
fun xnApiVersion(): Int = 1

@CName("xn_free_string")
fun xnFreeString(s: CPointer<ByteVar>?) = free(s)

// --- documents ----------------------------------------------------------------------------------

@CName("xn_note_open")
fun xnNoteOpen(path: String?, workDir: String?, host: CPointer<xn_host>?, error: CPointer<CPointerVar<ByteVar>>?): COpaquePointer? =
    try {
        requireNotNull(path) { "path is null" }
        val dir = workDir?.let { fileOf(it) }
        val doc = FileSystem.SYSTEM.source(path.toPath()).use { source ->
            codecFor(host).read(source.asInputStream(), dir, dir)
        }
        doc.path = path
        doc.displayName = path.substringAfterLast('/')
        StableRef.create(CNote(doc)).asCPointer()
    } catch (t: Throwable) {
        reportError(error, t)
        null
    }

@CName("xn_note_new")
fun xnNoteNew(pages: Int): COpaquePointer? =
    runCatching { StableRef.create(CNote(Document.blank(pages.coerceAtLeast(1)))).asCPointer() }.getOrNull()

@CName("xn_note_save")
fun xnNoteSave(note: COpaquePointer?, path: String?, host: CPointer<xn_host>?, error: CPointer<CPointerVar<ByteVar>>?): Int =
    try {
        requireNotNull(note) { "note is null" }
        requireNotNull(path) { "path is null" }
        val doc = note.note().document
        val target = path.toPath()
        val tmp = (target.parent ?: ".".toPath()) / ".${target.name}.saving"
        val fs = FileSystem.SYSTEM
        try {
            fs.openReadWrite(tmp, mustCreate = false, mustExist = false).use { handle ->
                handle.resize(0L)
                val sink = handle.sink()
                try {
                    codecFor(host).write(doc, sink.asOutputStream())
                } finally {
                    sink.close()
                }
                handle.flush() // on disk before the rename publishes it
            }
            try {
                fs.atomicMove(tmp, target)
            } catch (_: IOException) {
                fs.copy(tmp, target)
            }
        } finally {
            runCatching { fs.delete(tmp) }
        }
        doc.path = path
        doc.displayName = target.name
        doc.dirty = false
        1
    } catch (t: Throwable) {
        reportError(error, t)
        0
    }

@CName("xn_note_page_count")
fun xnNotePageCount(note: COpaquePointer?): Int = runCatching { note!!.note().document.pages.size }.getOrDefault(0)

@CName("xn_note_item_count")
fun xnNoteItemCount(note: COpaquePointer?, page: Int): Int =
    runCatching { note!!.note().document.pages[page].items.size }.getOrDefault(-1)

@CName("xn_note_is_dirty")
fun xnNoteIsDirty(note: COpaquePointer?): Int = runCatching { if (note!!.note().document.dirty) 1 else 0 }.getOrDefault(0)

@CName("xn_note_close")
fun xnNoteClose(note: COpaquePointer?) {
    runCatching { note?.asStableRef<CNote>()?.dispose() }
}

// --- editor -------------------------------------------------------------------------------------

internal class CEditor(note: CNote, private val host: xn_host) {
    private val scheduler = CScheduler(host)
    val history = History()
    private var palette: ChromePalette = ChromePalette.DARK
    private val surfaces = CSurfaceFactory(host)
    val state = CanvasState(note.document, surfaces, palette)
    val controller = InteractionController(
        state, history, CTextMeasurer(host),
        requestRender = { host.request_render!!.invoke(host.ctx) },
        scheduler = scheduler,
        chromePalette = { palette },
        onContentChanged = { host.content_changed!!.invoke(host.ctx) },
        onViewChanged = { host.view_changed!!.invoke(host.ctx) },
    )
    val editor = NoteEditor(state, history, controller, CTextMeasurer(host), object : NoteEditorHost {
        override fun requestRender() = host.request_render!!.invoke(host.ctx)
        override fun contentChanged() = host.content_changed!!.invoke(host.ctx)
        override fun viewChanged() = host.view_changed!!.invoke(host.ctx)
        override fun notice(notice: EditorNotice) = host.notice!!.invoke(
            host.ctx,
            when (notice) {
                EditorNotice.KEEP_ONE_PAGE -> 1
                EditorNotice.PAGE_ALREADY_EMPTY -> 2
            },
        )
    })
    private val sharpSettle = Runnable { state.requestSharpViewport() }

    init {
        // Page ruling; embedded PDF pages are not rendered yet, so they show only their margins.
        state.paintPageBackground = { page, renderer, _, region -> state.paintRuling(page, renderer, region) }
        controller.setTool(Tool.PEN)
    }

    /** Zoom or scroll moved outside a gesture: the host refreshes its view and repaints. */
    fun viewChanged() {
        host.view_changed!!.invoke(host.ctx)
        host.request_render!!.invoke(host.ctx)
    }

    fun setViewport(width: Int, height: Int, pxPerDp: Double) {
        val resized = width != state.viewportW
        state.devicePxPerDp = pxPerDp
        state.viewportW = width
        state.viewportH = height
        state.relayout()
        if (!state.didInitialFit) state.establishInitialView() else if (resized) state.reflowFitWidthForResize()
        state.clampScroll()
    }

    fun paint(target: COpaquePointer?) {
        surfaces.drain()
        val r = CRenderer(host.renderer!!.pointed, target)
        val frame = CanvasFramePainter.paint(r, state)
        controller.drawOverlay(r)
        CanvasFramePainter.finish(state, frame)
        scheduler.removeCallbacks(sharpSettle)
        if (frame.sharpSettleNeeded) scheduler.postDelayed(sharpSettle, SHARP_SETTLE_MS)
    }

    fun setPalette(p: ChromePalette) {
        palette = p
        state.palette = p
        state.invalidateAllCaches()
        host.request_render!!.invoke(host.ctx)
    }

    fun runTask(id: ULong) = scheduler.run(id)

    fun dispose() {
        scheduler.cancelAll()
        state.invalidateAllCaches()
        surfaces.dispose()
    }

    private companion object {
        const val SHARP_SETTLE_MS = 90L
    }
}


private inline fun <T> guard(fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    fallback
}

@CName("xn_editor_create")
fun xnEditorCreate(note: COpaquePointer?, host: CPointer<xn_host>?): COpaquePointer? = guard(null) {
    StableRef.create(CEditor(note!!.note(), copyHost(host!!))).asCPointer()
}

@CName("xn_editor_destroy")
fun xnEditorDestroy(editor: COpaquePointer?) = guard(Unit) {
    if (editor == null) return@guard
    val ref = editor.asStableRef<CEditor>()
    ref.get().dispose()
    ref.dispose()
}

@CName("xn_editor_set_viewport")
fun xnEditorSetViewport(editor: COpaquePointer?, width: Int, height: Int, pxPerDp: Double) =
    guard(Unit) { editor!!.editor().setViewport(width, height, pxPerDp) }

@CName("xn_editor_paint")
fun xnEditorPaint(editor: COpaquePointer?, renderer: COpaquePointer?) = guard(Unit) { editor!!.editor().paint(renderer) }

@CName("xn_editor_run_task")
fun xnEditorRunTask(editor: COpaquePointer?, task: ULong) = guard(Unit) { editor!!.editor().runTask(task) }

private fun event(e: xn_pointer_event): SimplePointerEvent {
    fun sample(p: xn_pointer) = PointerSample(p.id, p.x, p.y, p.pressure, p.tool_type)
    val pointers = List(e.pointer_count) { sample(e.pointers!![it]) }
    val history = List(e.history_size) { h ->
        e.history_time_ms!![h] to List(e.pointer_count) { i -> sample(e.history!![h * e.pointer_count + i]) }
    }
    return SimplePointerEvent(e.action, pointers, e.time_ms, e.action_index, e.button_state, history)
}

@CName("xn_editor_touch")
fun xnEditorTouch(editor: COpaquePointer?, e: CPointer<xn_pointer_event>?) = guard(Unit) {
    editor!!.editor().controller.onTouch(event(e!!.pointed))
}

@CName("xn_editor_hover")
fun xnEditorHover(editor: COpaquePointer?, e: CPointer<xn_pointer_event>?) = guard(Unit) {
    editor!!.editor().controller.onHover(event(e!!.pointed))
}

@CName("xn_editor_set_tool")
fun xnEditorSetTool(editor: COpaquePointer?, toolId: String?): Int = guard(0) {
    val tool = Tool.entries.firstOrNull { it.id == toolId } ?: return@guard 0
    editor!!.editor().editor.selectTool(tool)
    1
}

@CName("xn_editor_set_ink_color")
fun xnEditorSetInkColor(editor: COpaquePointer?, color: UInt) = guard(Unit) { editor!!.editor().controller.inkColor = unpack(color) }

@CName("xn_editor_undo")
fun xnEditorUndo(editor: COpaquePointer?) = guard(Unit) {
    val e = editor!!.editor()
    if (e.history.canUndo) e.editor.undo()
}

@CName("xn_editor_redo")
fun xnEditorRedo(editor: COpaquePointer?) = guard(Unit) {
    val e = editor!!.editor()
    if (e.history.canRedo) e.editor.redo()
}

@CName("xn_editor_can_undo")
fun xnEditorCanUndo(editor: COpaquePointer?): Int = guard(0) { if (editor!!.editor().history.canUndo) 1 else 0 }

@CName("xn_editor_can_redo")
fun xnEditorCanRedo(editor: COpaquePointer?): Int = guard(0) { if (editor!!.editor().history.canRedo) 1 else 0 }

@CName("xn_editor_escape")
fun xnEditorEscape(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().controller.escape() }

@CName("xn_editor_delete_selection")
fun xnEditorDeleteSelection(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().controller.deleteSelection() }

@CName("xn_editor_zoom_step")
fun xnEditorZoomStep(editor: COpaquePointer?, zoomIn: Int) = guard(Unit) {
    val e = editor!!.editor().editor
    if (zoomIn != 0) e.zoomIn() else e.zoomOut()
}

@CName("xn_editor_zoom_at")
fun xnEditorZoomAt(editor: COpaquePointer?, x: Double, y: Double, factor: Double) = guard(Unit) {
    val e = editor!!.editor()
    e.state.setZoomAnchored(Pt(x, y), e.state.zoom * factor)
    e.viewChanged()
}

@CName("xn_editor_scroll_by")
fun xnEditorScrollBy(editor: COpaquePointer?, dx: Double, dy: Double) = guard(Unit) {
    val e = editor!!.editor()
    e.state.scrollBy(dx, dy)
    e.viewChanged()
}

@CName("xn_editor_fit_width")
fun xnEditorFitWidth(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().editor.fitWidth() }

@CName("xn_editor_go_to_page")
fun xnEditorGoToPage(editor: COpaquePointer?, index: Int) = guard(Unit) { editor!!.editor().editor.goToPage(index) }

@CName("xn_editor_current_page")
fun xnEditorCurrentPage(editor: COpaquePointer?): Int = guard(0) { editor!!.editor().state.currentPageIndex() }

@CName("xn_editor_zoom")
fun xnEditorZoom(editor: COpaquePointer?): Double = guard(1.0) { editor!!.editor().state.zoom }

@CName("xn_editor_set_palette")
fun xnEditorSetPalette(editor: COpaquePointer?, palette: CPointer<xn_palette>?) = guard(Unit) {
    editor!!.editor().setPalette(CPalette(palette!!.pointed))
}

@CName("xn_editor_add_page")
fun xnEditorAddPage(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().editor.addPage() }

@CName("xn_editor_delete_current_page")
fun xnEditorDeleteCurrentPage(editor: COpaquePointer?) = guard(Unit) { editor!!.editor().editor.deleteCurrentPage() }

@Suppress("unused")
private fun keepImports(p: CPointer<ByteVar>?) = p?.toKString()
