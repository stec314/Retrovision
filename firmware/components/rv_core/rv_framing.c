#include "rv_framing.h"

#include <string.h>

static uint32_t crc_table[256];
static bool crc_table_ready;

static void crc_init(void)
{
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        crc_table[i] = c;
    }
    crc_table_ready = true;
}

uint32_t rv_crc32(const uint8_t *data, size_t len)
{
    // Benign race: concurrent first calls compute the same table.
    if (!crc_table_ready) {
        crc_init();
    }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; i++) {
        c = crc_table[(c ^ data[i]) & 0xFF] ^ (c >> 8);
    }
    return c ^ 0xFFFFFFFFu;
}

size_t rv_cobs_encode(const uint8_t *in, size_t len, uint8_t *out, size_t out_cap)
{
    if (out_cap < len + len / 254 + 1) {
        return 0;
    }
    size_t code_idx = 0;
    size_t o = 1;
    uint8_t code = 1;
    for (size_t i = 0; i < len; i++) {
        if (in[i] == 0) {
            out[code_idx] = code;
            code_idx = o++;
            code = 1;
        } else {
            out[o++] = in[i];
            if (++code == 0xFF) {
                out[code_idx] = code;
                code_idx = o++;
                code = 1;
            }
        }
    }
    out[code_idx] = code;
    return o;
}

long rv_cobs_decode(const uint8_t *in, size_t len, uint8_t *out, size_t out_cap)
{
    size_t i = 0, o = 0;
    while (i < len) {
        uint8_t code = in[i++];
        if (code == 0) {
            return -1;
        }
        size_t end = i + code - 1;
        if (end > len) {
            return -1;
        }
        for (; i < end; i++) {
            if (in[i] == 0 || o >= out_cap) {
                return -1;
            }
            out[o++] = in[i];
        }
        if (code != 0xFF && i < len) {
            if (o >= out_cap) {
                return -1;
            }
            out[o++] = 0;
        }
    }
    return (long)o;
}

size_t rv_frame_encode(const uint8_t *envelope, size_t len, uint8_t *out, size_t out_cap)
{
    if (len > RV_MAX_ENVELOPE) {
        return 0;
    }
    const size_t total = len + RV_CRC_LEN;
    if (out_cap < total + total / 254 + 2) { // COBS worst case + delimiter
        return 0;
    }
    uint32_t crc = rv_crc32(envelope, len);
    const uint8_t crc_le[RV_CRC_LEN] = {(uint8_t)crc, (uint8_t)(crc >> 8), (uint8_t)(crc >> 16),
                                        (uint8_t)(crc >> 24)};

    // COBS over the virtual concatenation envelope || crc_le, no temp copy
    // (keeps task stacks small on the probe).
    size_t code_idx = 0;
    size_t o = 1;
    uint8_t code = 1;
    for (size_t i = 0; i < total; i++) {
        uint8_t b = i < len ? envelope[i] : crc_le[i - len];
        if (b == 0) {
            out[code_idx] = code;
            code_idx = o++;
            code = 1;
        } else {
            out[o++] = b;
            if (++code == 0xFF) {
                out[code_idx] = code;
                code_idx = o++;
                code = 1;
            }
        }
    }
    out[code_idx] = code;
    out[o++] = 0x00;
    return o;
}

void rv_frame_decoder_init(rv_frame_decoder_t *d)
{
    d->len = 0;
    d->overflow = false;
    d->bad_frames = 0;
}

rv_frame_result_t rv_frame_decoder_feed(rv_frame_decoder_t *d, uint8_t byte,
                                        const uint8_t **envelope, size_t *envelope_len)
{
    if (byte != 0x00) {
        if (d->overflow) {
            return RV_FRAME_NONE;
        }
        if (d->len >= RV_MAX_ENCODED_FRAME) {
            d->overflow = true;
            d->len = 0;
            return RV_FRAME_NONE;
        }
        d->buf[d->len++] = byte;
        return RV_FRAME_NONE;
    }

    // Delimiter.
    if (d->overflow) {
        d->overflow = false;
        d->len = 0;
        d->bad_frames++;
        return RV_FRAME_BAD;
    }
    if (d->len == 0) {
        return RV_FRAME_NONE; // empty frame, legal
    }
    long n = rv_cobs_decode(d->buf, d->len, d->decoded, sizeof d->decoded);
    d->len = 0;
    if (n < RV_CRC_LEN + 1) {
        d->bad_frames++;
        return RV_FRAME_BAD;
    }
    size_t body = (size_t)n - RV_CRC_LEN;
    uint32_t want = (uint32_t)d->decoded[body] | ((uint32_t)d->decoded[body + 1] << 8) |
                    ((uint32_t)d->decoded[body + 2] << 16) | ((uint32_t)d->decoded[body + 3] << 24);
    if (rv_crc32(d->decoded, body) != want) {
        d->bad_frames++;
        return RV_FRAME_BAD;
    }
    *envelope = d->decoded;
    *envelope_len = body;
    return RV_FRAME_OK;
}
