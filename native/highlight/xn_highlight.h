// Host-neutral code highlighting over the vendored tree-sitter: parse a code
// block, run a highlight query (.scm) and return flat capture spans. Plain C,
// no JNI, so the Android bridge (app/src/main/cpp/ts_jni.c) and a desktop host
// share one implementation. Calls are stateless; no handles outlive a call.
#ifndef XN_HIGHLIGHT_H
#define XN_HIGHLIGHT_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// One capture: [start, end) in UTF-8 bytes of the text, capture index into
// the names xn_capture_names returns for the same query.
typedef struct {
    uint32_t start;
    uint32_t end;
    uint32_t capture;
} XnSpan;

bool xn_language_supported(const char *lang);

// Spans for text under the query scm. On success returns true and hands over
// a malloc'd array in *out (free with xn_free_spans; NULL when *out_count is 0).
// Returns false on unknown language, malformed query, parse failure or OOM.
bool xn_highlight(const char *lang,
                  const char *text, uint32_t text_len,
                  const char *scm, uint32_t scm_len,
                  XnSpan **out, uint32_t *out_count);

void xn_free_spans(XnSpan *spans);

// The query's capture names, indexed by XnSpan.capture. On success hands over
// *out_count malloc'd NUL-terminated strings (free with xn_free_names).
bool xn_capture_names(const char *lang,
                      const char *scm, uint32_t scm_len,
                      char ***out, uint32_t *out_count);

void xn_free_names(char **names, uint32_t count);

#ifdef __cplusplus
}
#endif

#endif
