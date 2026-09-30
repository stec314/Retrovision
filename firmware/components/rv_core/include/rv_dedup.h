// Probe-side dedup (docs/protocol.md §7.3). Portable C99, no allocation.
//
// Emit-first semantics: the first sighting of a key is forwarded immediately.
// Further sightings within `window_us` are suppressed and counted. The next
// sighting after the window has elapsed is forwarded again, carrying
//   merged_count = 1 + suppressed sightings since the previous forward.
// This keeps latency at zero and needs no payload storage; the host sees each
// device at most once per window.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RV_DEDUP_SLOTS 1024  // power of two
#define RV_DEDUP_PROBE 8     // linear probing distance

typedef struct {
    uint64_t key;            // 0 = empty
    uint64_t window_start_us;
    uint32_t suppressed;
} rv_dedup_slot_t;

typedef struct {
    rv_dedup_slot_t slots[RV_DEDUP_SLOTS];
    uint32_t evictions;
} rv_dedup_t;

void rv_dedup_init(rv_dedup_t *d);
void rv_dedup_clear(rv_dedup_t *d);

// 64-bit FNV-1a, chainable: start with RV_FNV_INIT.
#define RV_FNV_INIT 0xcbf29ce484222325ull
uint64_t rv_fnv1a(uint64_t h, const void *data, size_t len);

// Returns true if this sighting should be forwarded; *merged_count is then
// set to the value for Observation.merged_count (>= 1).
// window_us == 0 disables dedup (always forward, merged_count = 1).
bool rv_dedup_check(rv_dedup_t *d, uint64_t key, uint64_t now_us, uint64_t window_us,
                    uint32_t *merged_count);

#ifdef __cplusplus
}
#endif
