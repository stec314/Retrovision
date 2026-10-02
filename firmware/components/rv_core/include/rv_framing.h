// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Retrovision wire framing: COBS(envelope || CRC32_LE(envelope)) || 0x00
// Spec: docs/protocol.md §3. Portable C99, no ESP-IDF dependency.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RV_CRC_LEN 4
#define RV_MAX_DECODED_FRAME 1280
#define RV_MAX_ENCODED_FRAME (RV_MAX_DECODED_FRAME + RV_MAX_DECODED_FRAME / 254 + 1)
// Largest envelope that fits in a frame.
#define RV_MAX_ENVELOPE (RV_MAX_DECODED_FRAME - RV_CRC_LEN)
// Buffer size needed to hold one encoded frame including the trailing 0x00.
#define RV_FRAME_BUF_SIZE (RV_MAX_ENCODED_FRAME + 1)

// CRC-32/ISO-HDLC (same as zlib crc32, java.util.zip.CRC32).
uint32_t rv_crc32(const uint8_t *data, size_t len);

// COBS encode. Returns encoded length, or 0 if out_cap is too small.
size_t rv_cobs_encode(const uint8_t *in, size_t len, uint8_t *out, size_t out_cap);

// COBS decode. Returns decoded length, or -1 on malformed input / no space.
long rv_cobs_decode(const uint8_t *in, size_t len, uint8_t *out, size_t out_cap);

// Build a complete frame (with trailing 0x00) from envelope bytes.
// Returns frame length, or 0 if the envelope is too large or out_cap too small.
size_t rv_frame_encode(const uint8_t *envelope, size_t len, uint8_t *out, size_t out_cap);

typedef enum {
    RV_FRAME_NONE = 0,     // need more bytes
    RV_FRAME_OK,           // envelope available
    RV_FRAME_BAD,          // a frame was dropped (COBS/CRC/length/overflow)
} rv_frame_result_t;

typedef struct {
    uint8_t buf[RV_MAX_ENCODED_FRAME];
    uint8_t decoded[RV_MAX_DECODED_FRAME];
    size_t len;
    bool overflow;
    uint32_t bad_frames;
} rv_frame_decoder_t;

void rv_frame_decoder_init(rv_frame_decoder_t *d);

// Feed one byte. On RV_FRAME_OK, *envelope/*envelope_len point into the
// decoder and stay valid until the next call.
rv_frame_result_t rv_frame_decoder_feed(rv_frame_decoder_t *d, uint8_t byte,
                                        const uint8_t **envelope, size_t *envelope_len);

#ifdef __cplusplus
}
#endif
