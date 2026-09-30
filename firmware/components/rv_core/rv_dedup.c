#include "rv_dedup.h"

#include <string.h>

void rv_dedup_init(rv_dedup_t *d)
{
    memset(d, 0, sizeof *d);
}

void rv_dedup_clear(rv_dedup_t *d)
{
    memset(d->slots, 0, sizeof d->slots);
}

uint64_t rv_fnv1a(uint64_t h, const void *data, size_t len)
{
    const uint8_t *p = data;
    for (size_t i = 0; i < len; i++) {
        h ^= p[i];
        h *= 0x100000001b3ull;
    }
    return h;
}

bool rv_dedup_check(rv_dedup_t *d, uint64_t key, uint64_t now_us, uint64_t window_us,
                    uint32_t *merged_count)
{
    *merged_count = 1;
    if (window_us == 0) {
        return true;
    }
    if (key == 0) {
        key = 1; // 0 marks empty slots
    }

    const size_t mask = RV_DEDUP_SLOTS - 1;
    size_t idx = (size_t)(key ^ (key >> 32)) & mask;
    rv_dedup_slot_t *empty = NULL;
    rv_dedup_slot_t *oldest = NULL;

    // Scan the whole probe window (slots are never freed individually, so an
    // empty slot does not terminate the chain).
    for (int i = 0; i < RV_DEDUP_PROBE; i++) {
        rv_dedup_slot_t *s = &d->slots[(idx + (size_t)i) & mask];
        if (s->key == key) {
            if (now_us - s->window_start_us < window_us) {
                s->suppressed++;
                return false;
            }
            *merged_count = 1 + s->suppressed;
            s->window_start_us = now_us;
            s->suppressed = 0;
            return true;
        }
        if (s->key == 0) {
            if (empty == NULL) {
                empty = s;
            }
        } else if (oldest == NULL || s->window_start_us < oldest->window_start_us) {
            oldest = s;
        }
    }

    rv_dedup_slot_t *victim = empty != NULL ? empty : oldest;
    if (victim->key != 0) {
        d->evictions++; // suppressed count of the evicted key is lost
    }
    victim->key = key;
    victim->window_start_us = now_us;
    victim->suppressed = 0;
    return true;
}
