// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#include "rv_ble.h"

rv_ble_addr_type_t rv_ble_classify_addr(int is_random, const uint8_t addr_msb[6])
{
    if (!is_random) {
        return RV_BLE_ADDR_PUBLIC;
    }
    switch (addr_msb[0] >> 6) {
    case 0x3: return RV_BLE_ADDR_RANDOM_STATIC;
    case 0x1: return RV_BLE_ADDR_RANDOM_RESOLVABLE;
    case 0x0: return RV_BLE_ADDR_RANDOM_NON_RESOLVABLE;
    default: return RV_BLE_ADDR_UNSPECIFIED; // 10 is reserved
    }
}

void rv_ble_addr_reverse(const uint8_t in[6], uint8_t out[6])
{
    for (int i = 0; i < 6; i++) {
        out[i] = in[5 - i];
    }
}

int8_t rv_ble_tx_power(const uint8_t *ad, size_t len)
{
    size_t pos = 0;
    while (pos < len) {
        const uint8_t flen = ad[pos];
        if (flen == 0) {
            break; // early termination / padding
        }
        if (pos + 1 + flen > len) {
            break;
        }
        if (ad[pos + 1] == 0x0A && flen == 2) {
            return (int8_t)ad[pos + 2];
        }
        pos += 1 + (size_t)flen;
    }
    return 0;
}
