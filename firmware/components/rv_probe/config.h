// Runtime configuration: validated, clamped form of retrovision.v1.Config.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "retrovision.pb.h"

#define RV_MAX_HOP 64

typedef struct {
    uint8_t channel;
    uint16_t dwell_ms;
} rv_hop_t;

typedef struct {
    bool wifi_enabled;
    rv_hop_t hop[RV_MAX_HOP];
    uint8_t hop_count;
    uint32_t wifi_type_mask;   // bit n set = forward rv_wifi_type_t n
    bool forward_raw_ies;
    uint32_t probe_req_dedup_ms;
    uint32_t beacon_dedup_ms;
    int8_t wifi_min_rssi;      // -128 = no filter

    bool ble_enabled;
    bool ble_active;
    uint16_t ble_itvl;         // 0.625 ms units
    uint16_t ble_window;       // 0.625 ms units
    bool ble_extended;
    uint32_t ble_dedup_ms;
    int8_t ble_min_rssi;       // -128 = no filter

    retrovision_v1_RadioMode radio_mode;  // effective mode
    uint32_t status_interval_s;
    bool led_off;              // true = keep the status LED dark (covert)
} rv_cfg_t;

void rv_cfg_defaults(rv_cfg_t *cfg);

// Convert + clamp. Returns ACK_RESULT_OK or ACK_RESULT_PARTIAL; on PARTIAL,
// `msg` (cap bytes) explains the first adjustment made.
retrovision_v1_AckResult rv_cfg_from_pb(const retrovision_v1_Config *in, rv_cfg_t *out,
                                        char *msg, size_t cap);

// Channels this probe accepts (EU: 1..13; receive-only).
bool rv_cfg_channel_supported(uint32_t ch);
#define RV_CFG_MIN_CHANNEL 1
#define RV_CFG_MAX_CHANNEL 13

// Dual-band parts (ESP32-C5) can also tune 5 GHz. The sniffer is receive-only,
// so it may listen on DFS channels too; transmit never happens.
#if defined(CONFIG_IDF_TARGET_ESP32C5)
#define RV_HAS_5GHZ 1
#endif

// 5 GHz 20 MHz primary channels this firmware may hop to (empty array on 2.4-only parts).
extern const uint8_t rv_cfg_5ghz_channels[];
extern const uint8_t rv_cfg_5ghz_channel_count;
bool rv_cfg_channel_is_5ghz(uint32_t ch);
