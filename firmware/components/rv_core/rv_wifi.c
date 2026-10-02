// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#include "rv_wifi.h"

#include <string.h>

#define HDR_LEN 24

rv_wifi_type_t rv_wifi_classify(uint8_t fc0)
{
    const uint8_t version = fc0 & 0x03;
    const uint8_t type = (fc0 >> 2) & 0x03;
    const uint8_t subtype = (fc0 >> 4) & 0x0F;
    if (version != 0) {
        return RV_WIFI_UNSPECIFIED;
    }
    if (type == 0) { // management
        switch (subtype) {
        case 0x0: return RV_WIFI_ASSOC_REQ;
        case 0x2: return RV_WIFI_REASSOC_REQ;
        case 0x4: return RV_WIFI_PROBE_REQ;
        case 0x5: return RV_WIFI_PROBE_RESP;
        case 0x8: return RV_WIFI_BEACON;
        case 0xA: return RV_WIFI_DISASSOC;
        case 0xB: return RV_WIFI_AUTH;
        case 0xC: return RV_WIFI_DEAUTH;
        case 0xD: return RV_WIFI_ACTION;
        case 0xE: return RV_WIFI_ACTION; // action no-ack
        default: return RV_WIFI_UNSPECIFIED;
        }
    }
    if (type == 2) {
        return RV_WIFI_DATA;
    }
    return RV_WIFI_UNSPECIFIED;
}

// Offset of the first IE after the fixed fields of a management body,
// or -1 if the subtype carries no IEs we care about.
static int ie_offset(rv_wifi_type_t t)
{
    switch (t) {
    case RV_WIFI_PROBE_REQ: return HDR_LEN;
    case RV_WIFI_PROBE_RESP:
    case RV_WIFI_BEACON: return HDR_LEN + 12;       // timestamp(8) interval(2) capab(2)
    case RV_WIFI_ASSOC_REQ: return HDR_LEN + 4;     // capab(2) listen(2)
    case RV_WIFI_REASSOC_REQ: return HDR_LEN + 10;  // capab(2) listen(2) current AP(6)
    default: return -1;
    }
}

bool rv_wifi_parse(const uint8_t *f, size_t len, rv_wifi_frame_t *out)
{
    memset(out, 0, sizeof *out);
    // Management and data headers are at least 24 bytes (control frames,
    // which are shorter, are never forwarded).
    if (len < HDR_LEN) {
        return false;
    }
    out->type = rv_wifi_classify(f[0]);
    if (out->type == RV_WIFI_UNSPECIFIED) {
        return false;
    }
    memcpy(out->addr1, f + 4, 6);
    memcpy(out->addr2, f + 10, 6);
    memcpy(out->addr3, f + 16, 6);
    out->seq = (uint16_t)((f[22] | (f[23] << 8)) >> 4);

    if ((out->type == RV_WIFI_BEACON || out->type == RV_WIFI_PROBE_RESP) && len >= HDR_LEN + 8) {
        uint64_t t = 0;
        for (int i = 7; i >= 0; i--) {
            t = (t << 8) | f[HDR_LEN + i];
        }
        out->tsf = t;
    }

    int off = ie_offset(out->type);
    if (off < 0 || (size_t)off > len) {
        return true; // header-only observation (auth, deauth, data, ...)
    }

    const uint8_t *ies = f + off;
    const size_t avail = len - (size_t)off;
    size_t pos = 0;
    size_t keep = 0; // bytes of whole elements that fit the budget
    bool truncated = false;
    while (pos + 2 <= avail) {
        const uint8_t id = ies[pos];
        const uint8_t elen = ies[pos + 1];
        if (pos + 2 + elen > avail) {
            truncated = true; // malformed trailing element
            break;
        }
        if (id == 0 && !out->has_ssid && elen <= RV_MAX_SSID) {
            out->has_ssid = true;
            out->ssid_len = elen;
            memcpy(out->ssid, ies + pos + 2, elen);
        }
        pos += 2 + (size_t)elen;
        if (pos <= RV_MAX_RAW_IES) {
            keep = pos;
        } else {
            truncated = true;
            // keep walking only to find the SSID; it is normally first anyway
        }
    }
    if (pos < avail && !truncated && avail - pos == 1) {
        truncated = true; // single dangling byte
    }
    out->ies = ies;
    out->ies_len = (uint16_t)keep;
    out->ies_truncated = truncated;
    return true;
}
