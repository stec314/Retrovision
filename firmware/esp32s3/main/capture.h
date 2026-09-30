// Capture pipeline: radio callbacks -> raw queue -> dedup -> Observation frames.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "config.h"

#define RV_RAW_WIFI_MAX 512   // bytes of 802.11 frame kept (IEs are cut to 320 later)
#define RV_RAW_BLE_MAX 251

typedef enum {
    RV_RAW_WIFI = 1,
    RV_RAW_BLE = 2,
} rv_raw_kind_t;

typedef struct {
    uint8_t kind;
    int8_t rssi;
    uint8_t channel;
    uint8_t cut;            // Wi-Fi: frame longer than RV_RAW_WIFI_MAX, copy truncated
    uint16_t len;
    int64_t ts_us;
    union {
        struct {
            uint8_t frame[RV_RAW_WIFI_MAX];
        } wifi;
        struct {
            uint8_t addr[6];        // as received (LSB first)
            uint8_t addr_random;
            uint8_t adv_type;       // retrovision.v1.BleAdvType
            uint8_t prim_phy;
            uint8_t sec_phy;
            int8_t tx_power;        // 127 = unknown
            uint8_t data[RV_RAW_BLE_MAX];
        } ble;
    };
} rv_raw_item_t;

void rv_capture_init(void);

// Start/stop radios according to cfg. Stop is synchronous.
void rv_capture_start(const rv_cfg_t *cfg);
void rv_capture_stop(void);
bool rv_capture_running(void);

// Called from radio callbacks. Never blocks; counts drops.
void rv_capture_submit(const rv_raw_item_t *item);

// Active config, valid while running. Read-only for radio callbacks.
const rv_cfg_t *rv_capture_cfg(void);

typedef struct {
    uint32_t wifi_seen, wifi_sent, ble_seen, ble_sent, dropped;
} rv_capture_stats_t;
void rv_capture_stats(rv_capture_stats_t *out);

// Counters incremented by the radio modules (single writer each).
extern volatile uint32_t g_rv_wifi_seen;
extern volatile uint32_t g_rv_ble_seen;
