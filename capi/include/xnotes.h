/*
 * xnotes core — C API (v1).
 *
 * The Kotlin/Native build of the xnotes core (libxnotes.so) behind a small C interface for native
 * hosts such as the Qt application. The core keeps the document model, file formats, the paged
 * canvas (layout, page caches, frame composition) and pen/touch interaction; the host supplies
 * drawing, text measurement, image probing and timers through the callbacks in xn_host, and feeds
 * it input.
 *
 * Conventions
 *  - Every call is made on the host's UI thread. The core calls back on that thread, from inside the
 *    API call that caused it (v1 builds page caches synchronously).
 *  - Colours are 0xRRGGBBAA. Text is UTF-16 (the core's and QString's unit), with explicit lengths.
 *  - Coordinates handed to the renderer are in the renderer's current transform; the core sets up
 *    zoom and scroll itself with translate/scale.
 *  - Handles are owned by whoever created them and freed with the matching *_destroy/_close call.
 *    Strings the core returns are freed with xn_free_string.
 */
#ifndef XNOTES_H
#define XNOTES_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define XN_API_VERSION 1

typedef uint32_t xn_rgba; /* 0xRRGGBBAA */

typedef struct xn_note xn_note;     /* an open .xnote document */
typedef struct xn_editor xn_editor; /* a paged-canvas editor over a note */

/* --- drawing -------------------------------------------------------------------------------- */

enum { XN_BLEND_SRC_OVER = 0, XN_BLEND_MULTIPLY = 1, XN_BLEND_SCREEN = 2 };
enum { XN_FILL_NONZERO = 0, XN_FILL_EVEN_ODD = 1 };

typedef struct xn_pen {
    xn_rgba color;
    double width;
    int cosmetic;       /* width in device pixels, unaffected by the transform */
    int dashed;
    double dash_on, dash_gap, dash_phase;
} xn_pen;

typedef struct xn_font {
    double point_size;
    const uint16_t* face; /* family id, UTF-16 */
    int face_len;
    int bold, italic;
} xn_font;

/*
 * A drawing target. `r` is the host's own pointer: the one passed to xn_editor_paint, or one a
 * surface handed out through xn_host.surface_renderer. Every function is required.
 */
typedef struct xn_renderer_vt {
    void (*save)(void* r);
    void (*restore)(void* r);
    /* Offscreen layer over the rect, composited at `alpha` with `blend` on the matching restore. */
    void (*save_layer)(void* r, double x, double y, double w, double h, double alpha, int blend);
    void (*translate)(void* r, double dx, double dy);
    void (*scale)(void* r, double sx, double sy);
    void (*rotate)(void* r, double degrees); /* multiples of 90 */
    void (*clip_rect)(void* r, double x, double y, double w, double h);
    void (*clear)(void* r); /* clear the current clip to transparent */

    void (*fill_rect)(void* r, double x, double y, double w, double h, xn_rgba c);
    void (*fill_polygon)(void* r, const double* xy, int n, xn_rgba c, int fill_rule);
    void (*fill_ellipse)(void* r, double cx, double cy, double rx, double ry, xn_rgba c);
    /*
     * Ink: a disc of radius radii[i] at every centre (x, y interleaved) from `from` for `count`
     * points, plus the hull bridging consecutive discs, all in one colour. The hot path while
     * writing; filling it as one path keeps shared edges from seaming.
     */
    void (*fill_disk_ribbon)(void* r, const float* centers, const float* radii, int from, int count, xn_rgba c);

    void (*stroke_polyline)(void* r, const double* xy, int n, int closed, const xn_pen* pen);
    void (*stroke_rect)(void* r, double x, double y, double w, double h, const xn_pen* pen);
    void (*stroke_ellipse)(void* r, double cx, double cy, double rx, double ry, const xn_pen* pen);

    /* A surface (see xn_host.surface_create) scaled into dest; src may be NULL for the whole. */
    void (*draw_surface)(void* r, void* surface, const double* dest_xywh, const double* src_xywh,
                         double alpha, int blend);
    /* An image file (UTF-8 path) into dest, turned `orientation` degrees then `angle` radians about dest's centre. */
    void (*draw_image)(void* r, const char* path, double x, double y, double w, double h,
                       int orientation, double angle);
    void (*draw_text)(void* r, const uint16_t* text, int len, double x, double y, double w, double h,
                      const xn_font* font, xn_rgba c, int word_wrap, int align_left, int align_top);
    void (*draw_text_run)(void* r, const uint16_t* text, int len, double x, double baseline,
                          const xn_font* font, xn_rgba c);
} xn_renderer_vt;

/* --- host services ------------------------------------------------------------------------- */

enum { XN_NOTICE_KEEP_ONE_PAGE = 1, XN_NOTICE_PAGE_ALREADY_EMPTY = 2 };

typedef struct xn_host {
    void* ctx; /* passed back as the first argument of every service below */
    const xn_renderer_vt* renderer;

    /* Offscreen surfaces the core caches pages in (e.g. a QImage, transparent when created). */
    void* (*surface_create)(void* ctx, int width, int height, double device_pixel_ratio);
    void (*surface_fill)(void* ctx, void* surface, xn_rgba c);
    /* A renderer drawing into the surface, with an identity transform and no clip, fresh per call.
       It stays valid until the surface is next drawn with draw_surface or released. */
    void* (*surface_renderer)(void* ctx, void* surface);
    void (*surface_release)(void* ctx, void* surface);

    /* Text measurement, in page pixels (fonts are sized in points at 150 px per inch). */
    void (*text_measure)(void* ctx, const uint16_t* text, int len, const xn_font* font, double wrap_width,
                         int word_wrap, double* out_xywh);
    void (*text_metrics)(void* ctx, const xn_font* font, double* ascent, double* descent);
    /* The advance of each UTF-16 unit of text (len values into out). */
    void (*text_advances)(void* ctx, const uint16_t* text, int len, const xn_font* font, double* out);

    /* An image file's pixel size without decoding it; returns 0 when it cannot be read. */
    int (*image_probe)(void* ctx, const char* path, int* width, int* height);

    /* Something changed on screen: schedule a repaint (xn_editor_paint). */
    void (*request_render)(void* ctx);
    /* The document or the undo stack changed (refresh undo buttons, dirty marker, page count). */
    void (*content_changed)(void* ctx);
    /* Zoom, scroll or the current page changed. */
    void (*view_changed)(void* ctx);
    void (*notice)(void* ctx, int code);

    /* Timers. The host calls xn_editor_run_task(editor, task) when one fires. */
    void (*post_delayed)(void* ctx, uint64_t task, int64_t delay_ms);
    void (*cancel_task)(void* ctx, uint64_t task);
    /* Run the task on the next display frame (animations: fling, fade). */
    void (*post_frame)(void* ctx, uint64_t task);
} xn_host;

/* --- library -------------------------------------------------------------------------------- */

int xn_api_version(void);
void xn_free_string(char* s);

/* --- documents ----------------------------------------------------------------------------- */

/*
 * Open an .xnote. Embedded images and the source PDF are extracted into work_dir, which must exist
 * and should stay until the note is closed. On failure returns NULL and, when error is not NULL,
 * sets *error to a message (free with xn_free_string).
 */
xn_note* xn_note_open(const char* path, const char* work_dir, const xn_host* host, char** error);

/* A new note of `pages` blank A4 pages. */
xn_note* xn_note_new(int pages);

/* Save to path: written to a temp file beside it, flushed to disk, then renamed over it. 1 on success. */
int xn_note_save(xn_note* note, const char* path, const xn_host* host, char** error);

int xn_note_page_count(const xn_note* note);
int xn_note_item_count(const xn_note* note, int page);
int xn_note_is_dirty(const xn_note* note);
void xn_note_close(xn_note* note);

/* --- editor --------------------------------------------------------------------------------- */

/* An editor over note (which must outlive it). host is copied; its ctx must outlive the editor. */
xn_editor* xn_editor_create(xn_note* note, const xn_host* host);
void xn_editor_destroy(xn_editor* editor);

/* The view's size in device pixels, and device pixels per density-independent pixel. */
void xn_editor_set_viewport(xn_editor* editor, int width, int height, double device_px_per_dp);

/* Paint a frame into renderer (the host's target for host->renderer). */
void xn_editor_paint(xn_editor* editor, void* renderer);

/* A task posted through post_delayed/post_frame is due. */
void xn_editor_run_task(xn_editor* editor, uint64_t task);

/* --- input --- */

enum {
    XN_ACTION_DOWN = 0, XN_ACTION_UP = 1, XN_ACTION_MOVE = 2, XN_ACTION_CANCEL = 3,
    XN_ACTION_POINTER_DOWN = 5, XN_ACTION_POINTER_UP = 6,
    XN_ACTION_HOVER_MOVE = 7, XN_ACTION_HOVER_ENTER = 9, XN_ACTION_HOVER_EXIT = 10,
};
enum { XN_TOOL_TYPE_UNKNOWN = 0, XN_TOOL_TYPE_FINGER = 1, XN_TOOL_TYPE_STYLUS = 2, XN_TOOL_TYPE_MOUSE = 3, XN_TOOL_TYPE_ERASER = 4 };
enum { XN_BUTTON_STYLUS_PRIMARY = 32, XN_BUTTON_STYLUS_SECONDARY = 64 };

typedef struct xn_pointer {
    int id;
    int tool_type;
    float x, y;     /* viewport device pixels */
    float pressure; /* 0..1 */
} xn_pointer;

/*
 * One input event, shaped like Android's MotionEvent. history holds history_size earlier samples
 * coalesced into this event, oldest first: history_size * pointer_count pointers, sample-major,
 * with their times in history_time_ms. Tablets deliver many; pass them all for smooth ink.
 */
typedef struct xn_pointer_event {
    int action;
    int action_index; /* for POINTER_DOWN/UP */
    int button_state;
    int64_t time_ms;  /* monotonic */
    int pointer_count;
    const xn_pointer* pointers;
    int history_size;
    const int64_t* history_time_ms;
    const xn_pointer* history;
} xn_pointer_event;

void xn_editor_touch(xn_editor* editor, const xn_pointer_event* event);
void xn_editor_hover(xn_editor* editor, const xn_pointer_event* event);

/* --- tools and commands --- */

/* Tool ids as in the file format: "pen", "highlighter", "eraser", "pan", "lasso", "select", ... Returns 0 if unknown. */
int xn_editor_set_tool(xn_editor* editor, const char* tool_id);
void xn_editor_set_ink_color(xn_editor* editor, xn_rgba color);
void xn_editor_undo(xn_editor* editor);
void xn_editor_redo(xn_editor* editor);
int xn_editor_can_undo(const xn_editor* editor);
int xn_editor_can_redo(const xn_editor* editor);
void xn_editor_escape(xn_editor* editor);
void xn_editor_delete_selection(xn_editor* editor);

/* --- view --- */

void xn_editor_zoom_step(xn_editor* editor, int zoom_in);
/* Zoom about a viewport point, to zoom * factor. */
void xn_editor_zoom_at(xn_editor* editor, double x, double y, double factor);
void xn_editor_scroll_by(xn_editor* editor, double dx, double dy);
void xn_editor_fit_width(xn_editor* editor);
void xn_editor_go_to_page(xn_editor* editor, int index);
int xn_editor_current_page(const xn_editor* editor);
double xn_editor_zoom(const xn_editor* editor);

/* Canvas colours: gap, paper, page border, accent, dim text; chrome: panel, border, text, menu. */
typedef struct xn_palette {
    xn_rgba bg, paper, paper_border, accent, text_dim;
    xn_rgba panel, border, text, menu_bg;
    int is_dark;
} xn_palette;
void xn_editor_set_palette(xn_editor* editor, const xn_palette* palette);

/* --- pages --- */

void xn_editor_add_page(xn_editor* editor);
void xn_editor_delete_current_page(xn_editor* editor);

#ifdef __cplusplus
}
#endif

#endif /* XNOTES_H */
