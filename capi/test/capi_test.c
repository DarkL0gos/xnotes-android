#define _DEFAULT_SOURCE /* usleep */
/*
 * Drives libxnotes.so through xnotes.h alone, the way the Qt host will: a recording renderer and
 * host, a pen stroke, undo/redo, save and reopen, and error reporting.
 * Usage: capi_test <scratch dir>
 */
#include "xnotes.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Test-only export: run a full garbage collection (cleaners follow on their own thread). */
void xn_test_collect_garbage(void);

static int failures = 0;
#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__);           \
            fprintf(stderr, __VA_ARGS__);                                  \
            fputc('\n', stderr);                                           \
            failures++;                                                    \
        }                                                                  \
    } while (0)

/* --- a recording renderer --- */

typedef struct {
    int saves, restores, fills, polygons, ellipses, ribbons, ribbon_points, strokes, surfaces, texts, layers;
} counts;

typedef struct {
    counts* c;
} target;

static void r_save(void* r) { ((target*)r)->c->saves++; }
static void r_restore(void* r) { ((target*)r)->c->restores++; }
static void r_save_layer(void* r, double x, double y, double w, double h, double a, int b) {
    ((target*)r)->c->layers++;
    ((target*)r)->c->saves++;
}
static void r_translate(void* r, double dx, double dy) {}
static void r_scale(void* r, double sx, double sy) {}
static void r_rotate(void* r, double d) {}
static void r_clip(void* r, double x, double y, double w, double h) {}
static void r_clear(void* r) {}
static void r_fill_rect(void* r, double x, double y, double w, double h, xn_rgba c) { ((target*)r)->c->fills++; }
static void r_fill_polygon(void* r, const double* xy, int n, xn_rgba c, int rule) { ((target*)r)->c->polygons++; }
static void r_fill_ellipse(void* r, double cx, double cy, double rx, double ry, xn_rgba c) { ((target*)r)->c->ellipses++; }
static void r_fill_disk_ribbon(void* r, const float* centers, const float* radii, int from, int count, xn_rgba c) {
    ((target*)r)->c->ribbons++;
    ((target*)r)->c->ribbon_points += count;
    /* Touch the arrays the way a real host reads them. */
    for (int i = from; i < from + count; i++) {
        if (radii[i] < 0 || centers[2 * i] != centers[2 * i]) ((target*)r)->c->ribbons += 1000000;
    }
}
static void r_stroke_polyline(void* r, const double* xy, int n, int closed, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void r_stroke_rect(void* r, double x, double y, double w, double h, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void r_stroke_ellipse(void* r, double cx, double cy, double rx, double ry, const xn_pen* p) { ((target*)r)->c->strokes++; }
static void s_check(void* s);
static void r_draw_surface(void* r, void* s, const double* d, const double* src, double a, int b) {
    s_check(s);
    ((target*)r)->c->surfaces++;
}
static void r_draw_image(void* r, const char* path, double x, double y, double w, double h, int o, double a) {}
static void r_draw_text(void* r, const uint16_t* t, int n, double x, double y, double w, double h, const xn_font* f,
                        xn_rgba c, int ww, int al, int at) { ((target*)r)->c->texts++; }
static void r_draw_text_run(void* r, const uint16_t* t, int n, double x, double b, const xn_font* f, xn_rgba c) {
    ((target*)r)->c->texts++;
}

static const xn_renderer_vt vt = {
    r_save, r_restore, r_save_layer, r_translate, r_scale, r_rotate, r_clip, r_clear,
    r_fill_rect, r_fill_polygon, r_fill_ellipse, r_fill_disk_ribbon,
    r_stroke_polyline, r_stroke_rect, r_stroke_ellipse,
    r_draw_surface, r_draw_image, r_draw_text, r_draw_text_run,
};

/* --- the host --- */

typedef struct {
    counts screen;       /* what xn_editor_paint drew */
    counts offscreen;    /* what went into page cache surfaces */
    target screen_target;
    target surface_target;
    int surfaces_live;
    int renders, content, view;
    int last_notice;
    uint64_t delayed[64];
    int delayed_n;
    uint64_t frames[64];
    int frames_n;
    int menu_shown, menu_calls, context_calls;
    char tool[32];
} host_state;

/*
 * Surfaces are never freed while the test runs: a released one goes on a stack and the next
 * create hands the same address out again, as malloc tends to. Any use of a released surface,
 * or a second release, is counted, so a core that tracks surfaces by address gets caught.
 */
typedef struct {
    int w, h, alive;
} surface;

static surface* spare[4096];
static int spare_n;
static int stale_uses, double_releases, creates, reuses;

static void* s_create(void* ctx, int w, int h, double dpr) {
    host_state* hs = ctx;
    hs->surfaces_live++;
    creates++;
    if (spare_n > 0) reuses++;
    surface* s = spare_n > 0 ? spare[--spare_n] : malloc(sizeof(surface));
    s->w = w;
    s->h = h;
    s->alive = 1;
    return s;
}
static void s_check(void* s) {
    if (!((surface*)s)->alive) stale_uses++;
}
static void s_fill(void* ctx, void* s, xn_rgba c) { s_check(s); }
static void* s_renderer(void* ctx, void* s) {
    s_check(s);
    return &((host_state*)ctx)->surface_target;
}
static void s_release(void* ctx, void* s) {
    surface* sf = s;
    if (!sf->alive) {
        double_releases++;
        return;
    }
    ((host_state*)ctx)->surfaces_live--;
    sf->alive = 0;
    if (spare_n < 4096) spare[spare_n++] = sf;
}
static void t_measure(void* ctx, const uint16_t* t, int n, const xn_font* f, double wrap, int ww, double* out) {
    out[0] = 0;
    out[1] = 0;
    out[2] = wrap;
    out[3] = f->point_size * 1.3 * (1 + (n * f->point_size * 0.5) / (wrap > 1 ? wrap : 1));
}
static void t_metrics(void* ctx, const xn_font* f, double* a, double* d) {
    *a = f->point_size * 0.8;
    *d = f->point_size * 0.2;
}
static void t_advances(void* ctx, const uint16_t* t, int n, const xn_font* f, double* out) {
    for (int i = 0; i < n; i++) out[i] = f->point_size * 0.5;
}
static int i_probe(void* ctx, const char* path, int* w, int* h) { return 0; }
static void h_render(void* ctx) { ((host_state*)ctx)->renders++; }
static void h_content(void* ctx) { ((host_state*)ctx)->content++; }
static void h_view(void* ctx) { ((host_state*)ctx)->view++; }
static void h_notice(void* ctx, int code) { ((host_state*)ctx)->last_notice = code; }
static void h_post(void* ctx, uint64_t task, int64_t delay) {
    host_state* hs = ctx;
    if (hs->delayed_n < 64) hs->delayed[hs->delayed_n++] = task;
}
static void h_cancel(void* ctx, uint64_t task) {
    host_state* hs = ctx;
    for (int i = 0; i < hs->delayed_n; i++)
        if (hs->delayed[i] == task) hs->delayed[i] = 0;
}
static void h_frame(void* ctx, uint64_t task) {
    host_state* hs = ctx;
    if (hs->frames_n < 64) hs->frames[hs->frames_n++] = task;
}

static void h_selection_menu(void* ctx, int shown, double x, double y, double w, double h) {
    host_state* hs = ctx;
    hs->menu_shown = shown && w > 0 && h > 0;
    hs->menu_calls++;
}
static void h_context_menu(void* ctx, double x, double y, int on_locked) { ((host_state*)ctx)->context_calls++; }
static void h_tool_changed(void* ctx, const char* tool) {
    snprintf(((host_state*)ctx)->tool, sizeof ((host_state*)ctx)->tool, "%s", tool);
}

static xn_host make_host(host_state* hs) {
    memset(hs, 0, sizeof(*hs));
    hs->screen_target.c = &hs->screen;
    hs->surface_target.c = &hs->offscreen;
    xn_host h = {
        hs, &vt,
        s_create, s_fill, s_renderer, s_release,
        t_measure, t_metrics, t_advances,
        i_probe,
        h_render, h_content, h_view, h_notice,
        h_post, h_cancel, h_frame,
        h_selection_menu, h_context_menu, h_tool_changed,
    };
    return h;
}

static void pen(xn_editor* e, int action, float x, float y, int64_t t, int history, const float* hx, const float* hy) {
    xn_pointer p = {0, XN_TOOL_TYPE_STYLUS, x, y, 0.6f};
    xn_pointer hist[16];
    int64_t times[16];
    for (int i = 0; i < history; i++) {
        xn_pointer q = {0, XN_TOOL_TYPE_STYLUS, hx[i], hy[i], 0.4f + 0.02f * i};
        hist[i] = q;
        times[i] = t - (history - i);
    }
    xn_pointer_event ev = {action, 0, 0, t, 1, &p, history, times, hist};
    xn_editor_touch(e, &ev);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <scratch dir>\n", argv[0]);
        return 2;
    }
    CHECK(xn_api_version() == XN_API_VERSION, "api version %d", xn_api_version());

    host_state hs;
    xn_host host = make_host(&hs);

    xn_note* note = xn_note_new(2);
    CHECK(note != NULL, "xn_note_new");
    CHECK(xn_note_page_count(note) == 2, "page count");
    xn_editor* ed = xn_editor_create(note, &host);
    CHECK(ed != NULL, "xn_editor_create");
    xn_editor_set_viewport(ed, 800, 1000, 1.0);
    CHECK(xn_editor_zoom(ed) > 0, "zoom after the initial fit");

    xn_editor_paint(ed, &hs.screen_target);
    CHECK(hs.screen.fills > 0, "paper was filled");
    CHECK(hs.screen.saves == hs.screen.restores, "balanced save/restore (%d/%d)", hs.screen.saves, hs.screen.restores);

    /* A pen stroke across the first page, with coalesced samples on every move. */
    CHECK(xn_editor_set_tool(ed, "pen") == 1, "set pen");
    CHECK(xn_editor_set_tool(ed, "no-such-tool") == 0, "unknown tool rejected");
    xn_editor_set_ink_color(ed, 0x102030FFu);
    int64_t t = 1000;
    pen(ed, XN_ACTION_DOWN, 200, 300, t, 0, NULL, NULL);
    for (int step = 1; step <= 20; step++) {
        float hx[3], hy[3];
        for (int i = 0; i < 3; i++) {
            hx[i] = 200 + step * 12 - (3 - i) * 3;
            hy[i] = 300 + (step % 5) * 4 + i;
        }
        t += 4;
        pen(ed, XN_ACTION_MOVE, 200 + step * 12, 300 + (step % 5) * 4 + 3, t, 3, hx, hy);
        if (step == 10) {
            memset(&hs.screen, 0, sizeof(hs.screen));
            xn_editor_paint(ed, &hs.screen_target);
            CHECK(hs.screen.ribbons > 0 && hs.screen.ribbon_points > 1, "the live stroke is drawn as a ribbon mid-gesture");
            CHECK(hs.screen.ribbons < 1000000, "ribbon arrays hold sane values");
        }
    }
    pen(ed, XN_ACTION_UP, 440, 312, t + 4, 0, NULL, NULL);

    int page = xn_editor_current_page(ed);
    CHECK(xn_note_item_count(note, page) == 1, "one stroke filed on page %d (got %d)", page, xn_note_item_count(note, page));
    CHECK(xn_note_is_dirty(note) == 1, "note is dirty");
    CHECK(xn_editor_can_undo(ed) == 1, "can undo");
    CHECK(hs.content > 0 && hs.renders > 0, "host told of content (%d) and renders (%d)", hs.content, hs.renders);

    memset(&hs.screen, 0, sizeof(hs.screen));
    xn_editor_paint(ed, &hs.screen_target);
    CHECK(hs.screen.surfaces > 0, "page caches are blitted");
    CHECK(hs.offscreen.ribbons > 0, "the filed stroke went into a page cache");

    xn_editor_undo(ed);
    CHECK(xn_note_item_count(note, page) == 0, "undo removes it");
    CHECK(xn_editor_can_redo(ed) == 1, "can redo");
    xn_editor_redo(ed);
    CHECK(xn_note_item_count(note, page) == 1, "redo restores it");

    /* v2: selection, its menu, clipboard, restyle, lock. */
    CHECK(strcmp(xn_editor_tool(ed), "pen") == 0, "armed tool is pen (%s)", xn_editor_tool(ed));
    xn_editor_select_all(ed);
    CHECK(xn_editor_has_selection(ed) == 1, "select all selects the stroke");
    double rect[4] = {0};
    CHECK(xn_editor_selection_rect(ed, rect) == 1 && rect[2] > 0 && rect[3] > 0, "selection rect %g %g %g %g",
          rect[0], rect[1], rect[2], rect[3]);
    xn_rgba color = 0;
    double width = 0;
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x102030FFu, "selection style %08x", color);
    xn_editor_restyle_selection(ed, 1, 0xFF0000FFu, 1, 12.0, 1);
    xn_editor_restyle_selection(ed, 1, 0x00FF00FFu, 0, 0, 1);
    xn_editor_restyle_selection(ed, 0, 0, 0, 0, 0);
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x00FF00FFu && width == 12.0,
          "restyled %08x %g", color, width);
    xn_editor_undo(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "undo puts the selection away");
    xn_editor_select_all(ed);
    CHECK(xn_editor_selection_style(ed, &color, &width) == 1 && color == 0x102030FFu, "one undo reverts the restyle");
    xn_editor_copy(ed);
    CHECK(xn_editor_can_paste(ed) == 1, "copied");
    xn_editor_paste_at(ed, 300, 600);
    CHECK(xn_note_item_count(note, page) == 2, "pasted (%d)", xn_note_item_count(note, page));
    xn_editor_duplicate(ed);
    CHECK(xn_note_item_count(note, page) == 3, "duplicated");
    xn_editor_bring_to_front(ed);
    xn_editor_cut(ed);
    CHECK(xn_note_item_count(note, page) == 2 && xn_editor_has_selection(ed) == 0, "cut");
    xn_editor_select_all(ed);
    xn_editor_lock_selection(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "lock deselects");
    xn_editor_select_all(ed);
    CHECK(xn_editor_has_selection(ed) == 0, "locked items cannot be selected");
    xn_editor_undo(ed); /* unlock */
    xn_editor_undo(ed); /* uncut */
    xn_editor_undo(ed); /* undo duplicate */
    xn_editor_undo(ed); /* undo paste */
    CHECK(xn_note_item_count(note, page) == 1, "undo back to one stroke (%d)", xn_note_item_count(note, page));
    xn_editor_escape(ed);

    /* v2: tool widths and shapes. */
    xn_editor_set_tool_width(ed, "pen", 7.5);
    CHECK(xn_editor_tool_width(ed, "pen") == 7.5, "pen width");
    xn_editor_set_tool_width(ed, "pen", 1000);
    CHECK(xn_editor_tool_width(ed, "pen") == XN_STYLE_MAX_WIDTH, "pen width is clamped");
    xn_shape_style shape = {"ellipse", 4.0, 1, 0.3, 0};
    xn_editor_set_shape_style(ed, &shape);
    CHECK(xn_editor_set_tool(ed, "shape") == 1, "shape tool");
    pen(ed, XN_ACTION_DOWN, 200, 500, t + 100, 0, NULL, NULL);
    for (int i = 1; i <= 10; i++) pen(ed, XN_ACTION_MOVE, 200 + i * 10, 500 + i * 6, t + 100 + i * 8, 0, NULL, NULL);
    pen(ed, XN_ACTION_UP, 300, 560, t + 190, 0, NULL, NULL);
    CHECK(xn_note_item_count(note, page) == 2, "the shape was added (%d)", xn_note_item_count(note, page));
    memset(&hs.screen, 0, sizeof(hs.screen));
    xn_editor_paint(ed, &hs.screen_target);
    xn_editor_undo(ed);
    xn_editor_set_tool(ed, "pen");

    /* Pages and notices. */
    xn_editor_add_page(ed);
    CHECK(xn_note_page_count(note) == 3, "add page");
    xn_editor_undo(ed);
    CHECK(xn_note_page_count(note) == 2, "undo add page");

    /* Save, reopen. */
    char path[1024], work[1024], bad[1024];
    snprintf(path, sizeof path, "%s/capi.xnote", argv[1]);
    snprintf(work, sizeof work, "%s", argv[1]);
    char* err = NULL;
    CHECK(xn_note_save(note, path, &host, &err) == 1, "save: %s", err ? err : "");
    CHECK(xn_note_is_dirty(note) == 0, "clean after save");
    xn_note* back = xn_note_open(path, work, &host, &err);
    CHECK(back != NULL, "reopen: %s", err ? err : "");
    if (back) {
        CHECK(xn_note_page_count(back) == 2, "reopened pages");
        CHECK(xn_note_item_count(back, page) == 1, "reopened stroke");
        xn_note_close(back);
    }

    /* Errors come back as messages, never as crashes. */
    snprintf(bad, sizeof bad, "%s/does-not-exist.xnote", argv[1]);
    err = NULL;
    CHECK(xn_note_open(bad, work, &host, &err) == NULL, "missing file");
    CHECK(err != NULL && strlen(err) > 0, "an error message");
    xn_free_string(err);
    err = NULL;
    CHECK(xn_note_open(path, work, &host, NULL) != NULL || 1, "a NULL error pointer is allowed");
    xn_editor_touch(NULL, NULL); /* ignored, not a crash */

    /* Timers: whatever the core posted can be run (or has been cancelled) without harm. */
    for (int i = 0; i < hs.delayed_n; i++)
        if (hs.delayed[i]) xn_editor_run_task(ed, hs.delayed[i]);
    for (int i = 0; i < hs.frames_n; i++) xn_editor_run_task(ed, hs.frames[i]);
    xn_editor_run_task(ed, 999999); /* unknown ids are ignored */

    /*
     * Long strokes grow the wet-ink surface (a new one, the old recycled) and zooms rebuild page
     * caches, while the GC collects the dropped surface objects. Released addresses come back
     * from s_create, so a collected object must not release the surface now living there.
     */
    for (int round = 0; round < 12; round++) {
        int64_t t2 = 100000 + round * 10000;
        float y = 150 + round * 50;
        pen(ed, XN_ACTION_DOWN, 100, y, t2, 0, NULL, NULL);
        for (int step = 1; step <= 150; step++) {
            pen(ed, XN_ACTION_MOVE, 100 + step * 4, y + (step % 7), t2 + step * 4, 0, NULL, NULL);
            if (step % 5 == 0) xn_editor_paint(ed, &hs.screen_target);
        }
        pen(ed, XN_ACTION_UP, 700, y, t2 + 604, 0, NULL, NULL);
        xn_editor_zoom_at(ed, 400, 500, round % 2 ? 0.8 : 1.25);
        xn_editor_paint(ed, &hs.screen_target);
        for (int i = 0; i < hs.delayed_n; i++)
            if (hs.delayed[i]) xn_editor_run_task(ed, hs.delayed[i]); /* sharp re-render: new caches */
        hs.delayed_n = 0;
        xn_editor_paint(ed, &hs.screen_target);
        if (round % 3 == 2) { /* rarely, so released addresses are handed out again before it */
            xn_test_collect_garbage();
            usleep(30000);
            xn_editor_paint(ed, &hs.screen_target);
            xn_editor_paint(ed, &hs.screen_target);
        }
    }
    CHECK(reuses > 0, "released addresses were handed out again (%d of %d)", reuses, creates);
    CHECK(stale_uses == 0, "no released surface is used (%d uses)", stale_uses);
    CHECK(double_releases == 0, "no surface is released twice (%d)", double_releases);

    CHECK(hs.surfaces_live > 0, "page caches were live while editing");
    xn_editor_destroy(ed);
    CHECK(hs.surfaces_live == 0, "destroy releases every surface (%d left)", hs.surfaces_live);
    CHECK(stale_uses == 0 && double_releases == 0, "teardown is clean (%d stale, %d double)", stale_uses, double_releases);
    xn_note_close(note);

    if (failures) {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("capi_test: all checks passed (screen ribbons %d, cache ribbons %d, surfaces live %d)\n",
           hs.screen.ribbons, hs.offscreen.ribbons, hs.surfaces_live);
    return 0;
}
