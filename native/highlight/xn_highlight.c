// The JNI-free core of code highlighting; see xn_highlight.h.
#include "xn_highlight.h"

#include <regex.h>
#include <stdlib.h>
#include <string.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_bash(void);
const TSLanguage *tree_sitter_c(void);
const TSLanguage *tree_sitter_cpp(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_javascript(void);
const TSLanguage *tree_sitter_json(void);
const TSLanguage *tree_sitter_kotlin(void);
const TSLanguage *tree_sitter_python(void);

typedef struct {
    const char *name;
    const TSLanguage *(*fn)(void);
} LangEntry;

static const LangEntry LANGS[] = {
    {"bash", tree_sitter_bash},
    {"c", tree_sitter_c},
    {"cpp", tree_sitter_cpp},
    {"java", tree_sitter_java},
    {"javascript", tree_sitter_javascript},
    {"json", tree_sitter_json},
    {"kotlin", tree_sitter_kotlin},
    {"python", tree_sitter_python},
};

// --- query predicate evaluation ---
// ts_query returns #eq?/#match?-style predicates for the CALLER to check; without
// this, constrained captures over-apply (every identifier lights up as a constant).
// Supported: eq?, not-eq?, any-of?, not-any-of?, match?, not-match? (POSIX ERE).
// Unknown predicates and directives (#set! etc.) pass, so exotic queries degrade
// to over-highlighting instead of dropping out.

static bool match_capture_text(const TSQueryMatch *m, uint32_t value_id,
                               const char *text, const char **out, uint32_t *out_len) {
    for (uint16_t i = 0; i < m->capture_count; i++) {
        if (m->captures[i].index == value_id) {
            TSNode node = m->captures[i].node;
            uint32_t start = ts_node_start_byte(node);
            *out = text + start;
            *out_len = ts_node_end_byte(node) - start;
            return true;
        }
    }
    return false;
}

static bool regex_matches(const char *pattern, uint32_t plen, const char *s, uint32_t slen) {
    char *pat = malloc(plen + 1);
    char *str = malloc(slen + 1);
    bool result = false;
    if (pat && str) {
        memcpy(pat, pattern, plen);
        pat[plen] = 0;
        memcpy(str, s, slen);
        str[slen] = 0;
        regex_t re;
        if (regcomp(&re, pat, REG_EXTENDED | REG_NOSUB) == 0) {
            result = regexec(&re, str, 0, NULL, 0) == 0;
            regfree(&re);
        }
    }
    free(pat);
    free(str);
    return result;
}

static bool pattern_predicates_pass(const TSQuery *query, const TSQueryMatch *m, const char *text) {
    uint32_t step_count;
    const TSQueryPredicateStep *steps =
        ts_query_predicates_for_pattern(query, m->pattern_index, &step_count);
    uint32_t i = 0;
    while (i < step_count) {
        // One predicate runs from here to the next Done step.
        uint32_t end = i;
        while (end < step_count && steps[end].type != TSQueryPredicateStepTypeDone) end++;
        if (steps[i].type == TSQueryPredicateStepTypeString && end > i) {
            uint32_t name_len;
            const char *name = ts_query_string_value_for_id(query, steps[i].value_id, &name_len);
            bool negate = strncmp(name, "not-", 4) == 0;
            const char *op = negate ? name + 4 : name;
            uint32_t op_len = negate ? name_len - 4 : name_len;
            bool known = false;
            bool pass = true;
            const char *cap_text = NULL;
            uint32_t cap_len = 0;
            bool have_cap = end > i + 1 &&
                steps[i + 1].type == TSQueryPredicateStepTypeCapture &&
                match_capture_text(m, steps[i + 1].value_id, text, &cap_text, &cap_len);

            if (op_len == 3 && strncmp(op, "eq?", 3) == 0 && have_cap && end > i + 2) {
                known = true;
                if (steps[i + 2].type == TSQueryPredicateStepTypeString) {
                    uint32_t vlen;
                    const char *v = ts_query_string_value_for_id(query, steps[i + 2].value_id, &vlen);
                    pass = cap_len == vlen && strncmp(cap_text, v, vlen) == 0;
                } else {
                    const char *other = NULL;
                    uint32_t other_len = 0;
                    pass = match_capture_text(m, steps[i + 2].value_id, text, &other, &other_len) &&
                        cap_len == other_len && strncmp(cap_text, other, cap_len) == 0;
                }
            } else if (op_len == 6 && strncmp(op, "match?", 6) == 0 && have_cap && end > i + 2 &&
                       steps[i + 2].type == TSQueryPredicateStepTypeString) {
                known = true;
                uint32_t plen;
                const char *pat = ts_query_string_value_for_id(query, steps[i + 2].value_id, &plen);
                pass = regex_matches(pat, plen, cap_text, cap_len);
            } else if (op_len == 7 && strncmp(op, "any-of?", 7) == 0 && have_cap) {
                known = true;
                pass = false;
                for (uint32_t a = i + 2; a < end; a++) {
                    if (steps[a].type != TSQueryPredicateStepTypeString) continue;
                    uint32_t vlen;
                    const char *v = ts_query_string_value_for_id(query, steps[a].value_id, &vlen);
                    if (cap_len == vlen && strncmp(cap_text, v, vlen) == 0) {
                        pass = true;
                        break;
                    }
                }
            }
            if (known) {
                if (negate) pass = !pass;
                if (!pass) return false;
            }
        }
        i = end + 1;
    }
    return true;
}

static const TSLanguage *lang_for(const char *name) {
    if (!name) return NULL;
    for (size_t i = 0; i < sizeof(LANGS) / sizeof(LANGS[0]); i++) {
        if (strcmp(LANGS[i].name, name) == 0) return LANGS[i].fn();
    }
    return NULL;
}

bool xn_language_supported(const char *lang) {
    return lang_for(lang) != NULL;
}

bool xn_highlight(const char *lang,
                  const char *text, uint32_t text_len,
                  const char *scm, uint32_t scm_len,
                  XnSpan **out, uint32_t *out_count) {
    *out = NULL;
    *out_count = 0;
    const TSLanguage *language = lang_for(lang);
    if (!language || !text || !scm) return false;

    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = ts_query_new(language, scm, scm_len, &err_offset, &err_type);
    if (!query) return false;

    TSParser *parser = ts_parser_new();
    ts_parser_set_language(parser, language);
    TSTree *tree = ts_parser_parse_string(parser, NULL, text, text_len);

    bool ok = false;
    if (tree) {
        TSQueryCursor *cursor = ts_query_cursor_new();
        ts_query_cursor_exec(cursor, query, ts_tree_root_node(tree));
        uint32_t cap = 256, n = 0;
        XnSpan *buf = malloc(cap * sizeof(XnSpan));
        TSQueryMatch match;
        while (buf && ts_query_cursor_next_match(cursor, &match)) {
            if (!pattern_predicates_pass(query, &match, text)) continue;
            for (uint16_t i = 0; i < match.capture_count; i++) {
                if (n == cap) {
                    cap *= 2;
                    XnSpan *grown = realloc(buf, cap * sizeof(XnSpan));
                    if (!grown) { free(buf); buf = NULL; break; }
                    buf = grown;
                }
                TSNode node = match.captures[i].node;
                buf[n].start = ts_node_start_byte(node);
                buf[n].end = ts_node_end_byte(node);
                buf[n].capture = match.captures[i].index;
                n++;
            }
        }
        if (buf) {
            ok = true;
            if (n > 0) {
                *out = buf;
                *out_count = n;
            } else {
                free(buf);
            }
        }
        ts_query_cursor_delete(cursor);
        ts_tree_delete(tree);
    }
    ts_parser_delete(parser);
    ts_query_delete(query);
    return ok;
}

void xn_free_spans(XnSpan *spans) {
    free(spans);
}

bool xn_capture_names(const char *lang,
                      const char *scm, uint32_t scm_len,
                      char ***out, uint32_t *out_count) {
    *out = NULL;
    *out_count = 0;
    const TSLanguage *language = lang_for(lang);
    if (!language || !scm) return false;
    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = ts_query_new(language, scm, scm_len, &err_offset, &err_type);
    if (!query) return false;

    uint32_t count = ts_query_capture_count(query);
    char **names = calloc(count ? count : 1, sizeof(char *));
    bool ok = names != NULL;
    for (uint32_t i = 0; ok && i < count; i++) {
        uint32_t len;
        const char *name = ts_query_capture_name_for_id(query, i, &len);
        names[i] = malloc(len + 1);
        if (!names[i]) {
            ok = false;
            break;
        }
        memcpy(names[i], name, len);
        names[i][len] = 0;
    }
    ts_query_delete(query);
    if (!ok) {
        xn_free_names(names, count);
        return false;
    }
    *out = names;
    *out_count = count;
    return true;
}

void xn_free_names(char **names, uint32_t count) {
    if (!names) return;
    for (uint32_t i = 0; i < count; i++) free(names[i]);
    free(names);
}
