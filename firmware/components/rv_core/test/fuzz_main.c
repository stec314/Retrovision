// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Host fuzz smoke test for the parsers, built with ASan+UBSan:
//   cc -fsanitize=address,undefined -I../include ../*.c fuzz_main.c && ./a.out
// Not a coverage-guided fuzzer; a cheap guard against out-of-bounds bugs.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rv_ble.h"
#include "rv_dedup.h"
#include "rv_framing.h"
#include "rv_wifi.h"

static uint64_t rng = 0x9E3779B97F4A7C15ull;
static uint32_t next(void)
{
    rng ^= rng << 13;
    rng ^= rng >> 7;
    rng ^= rng << 17;
    return (uint32_t)rng;
}

int main(int argc, char **argv)
{
    long iters = argc > 1 ? atol(argv[1]) : 200000;
    static uint8_t buf[2048], out[2048];
    static rv_frame_decoder_t dec;
    static rv_dedup_t dd;
    rv_frame_decoder_init(&dec);
    rv_dedup_init(&dd);

    for (long it = 0; it < iters; it++) {
        size_t len = next() % sizeof buf;
        for (size_t i = 0; i < len; i++) {
            // bias towards small values so IE lengths are often "almost right"
            buf[i] = (next() & 3) ? (uint8_t)(next() % 40) : (uint8_t)next();
        }
        if (it & 1) {
            buf[0] = (uint8_t)((next() % 16) << 4); // valid mgmt subtypes
        }

        rv_wifi_frame_t wf;
        if (rv_wifi_parse(buf, len, &wf)) {
            if (wf.ies_len > RV_MAX_RAW_IES || wf.ssid_len > RV_MAX_SSID) {
                fprintf(stderr, "bounds violated\n");
                return 1;
            }
            if (wf.ies_len && (wf.ies < buf || wf.ies + wf.ies_len > buf + len)) {
                fprintf(stderr, "ies outside input\n");
                return 1;
            }
        }
        (void)rv_ble_tx_power(buf, len % 252);
        (void)rv_cobs_decode(buf, len, out, sizeof out);
        for (size_t i = 0; i < len; i++) {
            const uint8_t *env;
            size_t el;
            (void)rv_frame_decoder_feed(&dec, buf[i], &env, &el);
        }
        size_t n = rv_frame_encode(buf, len % (RV_MAX_ENVELOPE + 50), out, sizeof out);
        if ((len % (RV_MAX_ENVELOPE + 50)) <= RV_MAX_ENVELOPE && n == 0) {
            fprintf(stderr, "encode refused valid length\n");
            return 1;
        }
        uint32_t mc;
        (void)rv_dedup_check(&dd, next() % 5000, (uint64_t)it * 1000, 50000, &mc);
    }
    printf("fuzz ok: %ld iterations, %u bad frames, %u evictions\n", iters, dec.bad_frames,
           dd.evictions);
    return 0;
}
