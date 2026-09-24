// Smoke test for the host-neutral highlight API against the shipped .scm
// queries. Usage: xn_highlight_test <dir with <lang>.scm>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "xn_highlight.h"

static int failures = 0;

#define CHECK(cond, ...)                          \
    do {                                          \
        if (!(cond)) {                            \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__);         \
            fputc('\n', stderr);                  \
            failures++;                           \
        }                                         \
    } while (0)

static char *read_file(const char *path, uint32_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc((size_t)size + 1);
    if (buf && fread(buf, 1, (size_t)size, f) != (size_t)size) {
        free(buf);
        buf = NULL;
    }
    fclose(f);
    if (buf) buf[size] = 0;
    *len = (uint32_t)size;
    return buf;
}

static const struct {
    const char *lang;
    const char *code;
} SAMPLES[] = {
    {"bash", "echo \"hi\" # c\n"},
    {"c", "int main(void) { return 0; }\n"},
    {"cpp", "class A { public: int x = 1; };\n"},
    {"java", "class A { int f() { return 1; } }\n"},
    {"javascript", "const f = (x) => x + 1; // c\n"},
    {"json", "{\"a\": 1, \"b\": [true, null]}"},
    {"kotlin", "fun f(x: Int): Int = x + 1\n"},
    {"python", "def f(x):\n    return x + 1  # c\n"},
};

// Byte offsets of the JSON number "1" in SAMPLES' json entry.
static void check_json_number(const XnSpan *spans, uint32_t n, char **names, uint32_t name_count) {
    int found = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (spans[i].capture < name_count && strcmp(names[spans[i].capture], "number") == 0 &&
            spans[i].start == 6 && spans[i].end == 7) found = 1;
    }
    CHECK(found, "json: no 'number' capture over bytes [6,7)");
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <scm dir>\n", argv[0]);
        return 2;
    }
    for (size_t s = 0; s < sizeof(SAMPLES) / sizeof(SAMPLES[0]); s++) {
        const char *lang = SAMPLES[s].lang;
        char path[4096];
        snprintf(path, sizeof(path), "%s/%s.scm", argv[1], lang);
        uint32_t scm_len;
        char *scm = read_file(path, &scm_len);
        CHECK(scm != NULL, "%s: cannot read %s", lang, path);
        if (!scm) continue;
        CHECK(xn_language_supported(lang), "%s: not supported", lang);

        char **names;
        uint32_t name_count;
        CHECK(xn_capture_names(lang, scm, scm_len, &names, &name_count) && name_count > 0,
              "%s: capture names failed", lang);

        XnSpan *spans;
        uint32_t n;
        const char *code = SAMPLES[s].code;
        uint32_t code_len = (uint32_t)strlen(code);
        CHECK(xn_highlight(lang, code, code_len, scm, scm_len, &spans, &n) && n > 0,
              "%s: highlight produced no spans", lang);
        for (uint32_t i = 0; i < n; i++) {
            CHECK(spans[i].start <= spans[i].end && spans[i].end <= code_len,
                  "%s: span %u out of range", lang, i);
            CHECK(spans[i].capture < name_count, "%s: capture %u out of range", lang, i);
        }
        if (strcmp(lang, "json") == 0) check_json_number(spans, n, names, name_count);

        xn_free_spans(spans);
        xn_free_names(names, name_count);
        free(scm);
    }

    XnSpan *spans;
    uint32_t n;
    CHECK(!xn_language_supported("cobol"), "unknown language reported supported");
    CHECK(!xn_highlight("cobol", "x", 1, "(x) @x", 6, &spans, &n), "unknown language highlighted");
    CHECK(!xn_highlight("json", "{}", 2, "(not_a_node", 11, &spans, &n), "malformed query accepted");
    CHECK(xn_highlight("json", "", 0, "(number) @number", 16, &spans, &n) && n == 0 && spans == NULL,
          "empty text should succeed with no spans");

    if (failures) {
        fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    printf("xn_highlight_test: all checks passed\n");
    return 0;
}
