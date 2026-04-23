#include <oniguruma.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef struct {
    unsigned char *bytes;
    int32_t len;
} OniString;

static void ensure_initialized(void) {
    static int initialized = 0;
    if (!initialized) {
        OnigEncoding encs[] = { ONIG_ENCODING_UTF8 };
        onig_initialize(encs, 1);
        initialized = 1;
    }
}

int64_t oni_create_regex(const unsigned char *pattern, int32_t pattern_len) {
    ensure_initialized();
    regex_t *reg = NULL;
    OnigErrorInfo einfo;
    int r = onig_new(&reg,
                     pattern, pattern + pattern_len,
                     ONIG_OPTION_NONE,
                     ONIG_ENCODING_UTF8,
                     ONIG_SYNTAX_DEFAULT,
                     &einfo);
    if (r != ONIG_NORMAL) return 0;
    return (int64_t)(uintptr_t)reg;
}

void oni_free_regex(int64_t reg_ptr) {
    if (reg_ptr != 0) {
        onig_free((regex_t *)(uintptr_t)reg_ptr);
    }
}

int64_t oni_create_string(const unsigned char *bytes, int32_t len) {
    OniString *s = (OniString *)malloc(sizeof(OniString));
    if (!s) return 0;
    s->bytes = (unsigned char *)malloc((size_t)len);
    if (!s->bytes) { free(s); return 0; }
    memcpy(s->bytes, bytes, (size_t)len);
    s->len = len;
    return (int64_t)(uintptr_t)s;
}

void oni_free_string(int64_t str_ptr) {
    if (str_ptr != 0) {
        OniString *s = (OniString *)(uintptr_t)str_ptr;
        free(s->bytes);
        free(s);
    }
}

int32_t oni_match(int64_t reg_ptr, int64_t str_ptr, int32_t byte_offset,
                  int32_t match_begin_position, int32_t match_begin_string,
                  int32_t *regions_out, int32_t max_regions) {
    if (reg_ptr == 0 || str_ptr == 0) return -2;

    regex_t *reg = (regex_t *)(uintptr_t)reg_ptr;
    OniString *s  = (OniString *)(uintptr_t)str_ptr;

    OnigOptionType opts = ONIG_OPTION_NONE;
    if (!match_begin_position) opts |= ONIG_OPTION_NOT_BEGIN_POSITION;
    if (!match_begin_string)   opts |= ONIG_OPTION_NOT_BEGIN_STRING;

    OnigRegion *region = onig_region_new();
    const unsigned char *str   = s->bytes;
    const unsigned char *end   = str + s->len;
    const unsigned char *start = str + byte_offset;

    int r = onig_search(reg, str, end, start, end, region, opts);

    if (r == ONIG_MISMATCH) { onig_region_free(region, 1); return -1; }
    if (r < 0)              { onig_region_free(region, 1); return -2; }

    int32_t count = region->num_regs;
    int32_t fill  = (count < max_regions) ? count : max_regions;
    for (int32_t i = 0; i < fill; i++) {
        regions_out[i * 2]     = (int32_t)region->beg[i];
        regions_out[i * 2 + 1] = (int32_t)region->end[i];
    }
    onig_region_free(region, 1);
    return count;
}
