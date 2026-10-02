#include "config.h"

#include <stdio.h>
#include <string.h>

#include "rv_wifi.h"

#define DWELL_MIN_MS 50
#define DWELL_MAX_MS 5000
#define BLE_UNITS(ms) ((uint16_t)(((ms) * 1000u) / 625u))
#define BLE_MIN_UNITS 4      // 2.5 ms
#define BLE_MAX_UNITS 16384  // 10.24 s

// Primary channels (1, 6, 11) get twice the dwell and three times the visits:
// that is where most clients and APs live.
static const rv_hop_t k_default_hop[] = {
    {1, 200}, {6, 200}, {11, 200}, {2, 100},  {3, 100},  {1, 200}, {6, 200},
    {11, 200}, {4, 100}, {5, 100}, {7, 100},  {1, 200},  {6, 200}, {11, 200},
    {8, 100}, {9, 100}, {10, 100}, {12, 100}, {13, 100},
};

bool rv_cfg_channel_supported(uint32_t ch)
{
    return ch >= RV_CFG_MIN_CHANNEL && ch <= RV_CFG_MAX_CHANNEL;
}

static void wifi_defaults(rv_cfg_t *c)
{
    c->wifi_enabled = true;
    memcpy(c->hop, k_default_hop, sizeof k_default_hop);
    c->hop_count = sizeof k_default_hop / sizeof k_default_hop[0];
    c->wifi_type_mask = (1u << RV_WIFI_PROBE_REQ) | (1u << RV_WIFI_BEACON);
    c->forward_raw_ies = true;
    c->probe_req_dedup_ms = 0;
    c->beacon_dedup_ms = 30000;
    c->wifi_min_rssi = -128;
}

static void ble_defaults(rv_cfg_t *c)
{
    c->ble_enabled = true;
    c->ble_active = false;
    c->ble_itvl = BLE_UNITS(160);
    c->ble_window = BLE_UNITS(80);
    c->ble_extended = true;
    c->ble_dedup_ms = 1000;
    c->ble_min_rssi = -128;
}

void rv_cfg_defaults(rv_cfg_t *c)
{
    memset(c, 0, sizeof *c);
    wifi_defaults(c);
    ble_defaults(c);
    c->radio_mode = retrovision_v1_RadioMode_RADIO_MODE_COEX;
    c->status_interval_s = 5;
}

static int8_t clamp_rssi(int32_t v)
{
    if (v == 0) {
        return -128; // 0 = no filter
    }
    if (v < -127) {
        return -127;
    }
    if (v > 0) {
        return 0;
    }
    return (int8_t)v;
}

#define NOTE(...)                              \
    do {                                       \
        if (res == retrovision_v1_AckResult_ACK_RESULT_OK) { \
            snprintf(msg, cap, __VA_ARGS__);   \
        }                                      \
        res = retrovision_v1_AckResult_ACK_RESULT_PARTIAL; \
    } while (0)

retrovision_v1_AckResult rv_cfg_from_pb(const retrovision_v1_Config *in, rv_cfg_t *out,
                                        char *msg, size_t cap)
{
    retrovision_v1_AckResult res = retrovision_v1_AckResult_ACK_RESULT_OK;
    if (cap) {
        msg[0] = '\0';
    }
    rv_cfg_defaults(out);

    // An absent sub-message means "probe defaults" for that block. Inside a
    // present block, zero values mean: dedup off, no RSSI filter, default
    // hop / frame types / scan timing (see retrovision.proto).
    if (in->has_wifi) {
        const retrovision_v1_WifiConfig *w = &in->wifi;
        out->wifi_enabled = w->enabled;
        out->forward_raw_ies = w->forward_raw_ies;
        out->probe_req_dedup_ms = w->probe_req_dedup_ms;
        out->beacon_dedup_ms = w->beacon_dedup_ms;
        out->wifi_min_rssi = clamp_rssi(w->min_rssi_dbm);

        if (w->hop_count > 0) {
            uint8_t n = 0;
            for (pb_size_t i = 0; i < w->hop_count && n < RV_MAX_HOP; i++) {
                uint32_t ch = w->hop[i].channel;
                uint32_t dw = w->hop[i].dwell_ms;
                if (!rv_cfg_channel_supported(ch)) {
                    NOTE("channel %u unsupported, skipped", (unsigned)ch);
                    continue;
                }
                if (dw < DWELL_MIN_MS || dw > DWELL_MAX_MS) {
                    NOTE("dwell_ms clamped to [%d,%d]", DWELL_MIN_MS, DWELL_MAX_MS);
                    dw = dw < DWELL_MIN_MS ? DWELL_MIN_MS : DWELL_MAX_MS;
                }
                out->hop[n].channel = (uint8_t)ch;
                out->hop[n].dwell_ms = (uint16_t)dw;
                n++;
            }
            if (n == 0) {
                NOTE("no usable channel in hop list, using default");
            } else {
                out->hop_count = n;
            }
        }
        if (w->frame_types_count > 0) {
            out->wifi_type_mask = 0;
            for (pb_size_t i = 0; i < w->frame_types_count; i++) {
                uint32_t t = (uint32_t)w->frame_types[i];
                if (t >= RV_WIFI_PROBE_REQ && t <= RV_WIFI_ACTION) {
                    out->wifi_type_mask |= 1u << t;
                } else {
                    NOTE("frame type %u unknown, ignored", (unsigned)t);
                }
            }
        }
    }

    if (in->has_ble) {
        const retrovision_v1_BleConfig *b = &in->ble;
        out->ble_enabled = b->enabled;
        out->ble_active = b->active_scan;
        out->ble_extended = b->extended;
        out->ble_dedup_ms = b->dedup_ms;
        out->ble_min_rssi = clamp_rssi(b->min_rssi_dbm);
        if (b->scan_interval_ms || b->scan_window_ms) {
            uint32_t itvl = b->scan_interval_ms ? BLE_UNITS(b->scan_interval_ms) : out->ble_itvl;
            uint32_t win = b->scan_window_ms ? BLE_UNITS(b->scan_window_ms) : out->ble_window;
            if (itvl < BLE_MIN_UNITS || itvl > BLE_MAX_UNITS || win < BLE_MIN_UNITS ||
                win > BLE_MAX_UNITS || win > itvl) {
                NOTE("BLE scan timing clamped (2.5ms..10.24s, window<=interval)");
                if (itvl < BLE_MIN_UNITS) itvl = BLE_MIN_UNITS;
                if (itvl > BLE_MAX_UNITS) itvl = BLE_MAX_UNITS;
                if (win < BLE_MIN_UNITS) win = BLE_MIN_UNITS;
                if (win > itvl) win = itvl;
            }
            out->ble_itvl = (uint16_t)itvl;
            out->ble_window = (uint16_t)win;
        }
    }

    if (in->has_schedule) {
        switch (in->schedule.mode) {
        case retrovision_v1_RadioMode_RADIO_MODE_UNSPECIFIED:
        case retrovision_v1_RadioMode_RADIO_MODE_COEX:
            break;
        case retrovision_v1_RadioMode_RADIO_MODE_WIFI_ONLY:
            out->ble_enabled = false;
            out->radio_mode = in->schedule.mode;
            break;
        case retrovision_v1_RadioMode_RADIO_MODE_BLE_ONLY:
            out->wifi_enabled = false;
            out->radio_mode = in->schedule.mode;
            break;
        case retrovision_v1_RadioMode_RADIO_MODE_TIME_SLICED:
        default:
            NOTE("radio mode %d not implemented yet, using COEX", (int)in->schedule.mode);
            break;
        }
    }

    out->status_interval_s = in->status_interval_s;
    out->led_off = (in->led == retrovision_v1_LedMode_LED_MODE_OFF);
    return res;
}
