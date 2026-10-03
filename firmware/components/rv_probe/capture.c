// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#include "capture.h"

#include <string.h>

#include "ble_scanner.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "link.h"
#include "rv_ble.h"
#include "rv_dedup.h"
#include "rv_wifi.h"
#include "wifi_sniffer.h"

static const char *TAG = "capture";

#if CONFIG_IDF_TARGET_ESP32C5
// More RAM and two bands of traffic: a deeper queue rides out bursts (busy 5 GHz beacons).
#define QUEUE_DEPTH 96
#else
#define QUEUE_DEPTH 48
#endif

volatile uint32_t g_rv_wifi_seen;
volatile uint32_t g_rv_ble_seen;

static QueueHandle_t s_queue;
static volatile bool s_running;
static rv_cfg_t s_cfg;

// One writer each, so plain 32-bit counters are safe.
static volatile uint32_t s_drop_q_wifi, s_drop_q_ble, s_drop_link;
static volatile uint32_t s_wifi_sent, s_ble_sent;

// Owned by the pipeline task.
static rv_dedup_t s_dedup;
static retrovision_v1_Envelope s_env;
static rv_raw_item_t s_item;

const rv_cfg_t *rv_capture_cfg(void)
{
    return &s_cfg;
}

bool rv_capture_running(void)
{
    return s_running;
}

void rv_capture_submit(const rv_raw_item_t *item)
{
    if (!s_running) {
        return;
    }
    if (xQueueSend(s_queue, item, 0) != pdTRUE) {
        if (item->kind == RV_RAW_WIFI) {
            s_drop_q_wifi++;
        } else {
            s_drop_q_ble++;
        }
    }
}

// ASTM F3411 Remote ID over Wi-Fi Beacon: vendor IE with OUI FA:0B:BC, type 0x0D.
// Its payload (drone position) changes every frame while SSID and BSSID stay put,
// so the normal 30 s beacon dedup would hide almost all of the track.
static bool has_remote_id_ie(const uint8_t *ies, uint16_t len)
{
    uint16_t i = 0;
    while (i + 2 <= len) {
        const uint8_t id = ies[i], l = ies[i + 1];
        if (i + 2 + l > len) {
            break;
        }
        if (id == 221 && l >= 4 && ies[i + 2] == 0xFA && ies[i + 3] == 0x0B && ies[i + 4] == 0xBC &&
            ies[i + 5] == 0x0D) {
            return true;
        }
        i += 2 + l;
    }
    return false;
}

#define REMOTE_ID_DEDUP_MS 1000

static bool build_wifi(const rv_raw_item_t *it, retrovision_v1_Observation *obs)
{
    rv_wifi_frame_t f;
    if (!rv_wifi_parse(it->wifi.frame, it->len, &f)) {
        return false;
    }

    uint64_t key = rv_fnv1a(RV_FNV_INIT, "W", 1);
    key = rv_fnv1a(key, &f.type, sizeof f.type);
    key = rv_fnv1a(key, f.addr2, 6);
    key = rv_fnv1a(key, f.ssid, f.ssid_len);
    uint32_t window_ms = 0;
    if (f.type == RV_WIFI_PROBE_REQ) {
        window_ms = s_cfg.probe_req_dedup_ms;
    } else if (f.type == RV_WIFI_BEACON || f.type == RV_WIFI_PROBE_RESP) {
        window_ms = s_cfg.beacon_dedup_ms;
        if (window_ms > REMOTE_ID_DEDUP_MS && has_remote_id_ie(f.ies, f.ies_len)) {
            window_ms = REMOTE_ID_DEDUP_MS;
        }
    }
    uint32_t merged;
    if (!rv_dedup_check(&s_dedup, key, (uint64_t)it->ts_us, (uint64_t)window_ms * 1000, &merged)) {
        return false;
    }

    obs->probe_ts_us = (uint64_t)it->ts_us;
    obs->sensor = retrovision_v1_Sensor_SENSOR_WIFI;
    obs->rssi_dbm = it->rssi;
    obs->merged_count = merged;
    obs->which_detail = retrovision_v1_Observation_wifi_tag;
    retrovision_v1_WifiFrame *w = &obs->detail.wifi;
    w->frame_type = (retrovision_v1_WifiFrameType)f.type;
    w->channel = it->channel;
    w->addr1.size = 6;
    memcpy(w->addr1.bytes, f.addr1, 6);
    w->addr2.size = 6;
    memcpy(w->addr2.bytes, f.addr2, 6);
    w->addr3.size = 6;
    memcpy(w->addr3.bytes, f.addr3, 6);
    w->seq_ctrl = f.seq;
    w->tsf_us = f.tsf;
    w->ssid.size = f.ssid_len;
    memcpy(w->ssid.bytes, f.ssid, f.ssid_len);
    if (s_cfg.forward_raw_ies && f.ies_len > 0) {
        _Static_assert(sizeof w->raw_ies.bytes >= RV_MAX_RAW_IES, "raw_ies bound mismatch");
        w->raw_ies.size = f.ies_len;
        memcpy(w->raw_ies.bytes, f.ies, f.ies_len);
        w->raw_ies_truncated = f.ies_truncated || it->cut;
    }
    return true;
}

static bool build_ble(const rv_raw_item_t *it, retrovision_v1_Observation *obs)
{
    uint64_t key = rv_fnv1a(RV_FNV_INIT, "B", 1);
    key = rv_fnv1a(key, it->ble.addr, 6);
    key = rv_fnv1a(key, &it->ble.adv_type, 1);
    key = rv_fnv1a(key, it->ble.data, it->len);
    uint32_t merged;
    if (!rv_dedup_check(&s_dedup, key, (uint64_t)it->ts_us, (uint64_t)s_cfg.ble_dedup_ms * 1000,
                        &merged)) {
        return false;
    }

    obs->probe_ts_us = (uint64_t)it->ts_us;
    obs->sensor = retrovision_v1_Sensor_SENSOR_BLE;
    obs->rssi_dbm = it->rssi;
    obs->merged_count = merged;
    obs->which_detail = retrovision_v1_Observation_ble_tag;
    retrovision_v1_BleAdvertisement *b = &obs->detail.ble;
    uint8_t msb[6];
    rv_ble_addr_reverse(it->ble.addr, msb);
    b->address.size = 6;
    memcpy(b->address.bytes, msb, 6);
    b->address_type = (retrovision_v1_BleAddressType)rv_ble_classify_addr(it->ble.addr_random, msb);
    b->adv_type = (retrovision_v1_BleAdvType)it->ble.adv_type;
    // Scan responses are forwarded as their own observation (adv_type
    // SCAN_RSP) with the payload in scan_rsp_data; the host pairs them by
    // address. Pairing on the probe would need per-device state.
    if (b->adv_type == retrovision_v1_BleAdvType_BLE_ADV_TYPE_SCAN_RSP) {
        b->scan_rsp_data.size = it->len;
        memcpy(b->scan_rsp_data.bytes, it->ble.data, it->len);
    } else {
        b->adv_data.size = it->len;
        memcpy(b->adv_data.bytes, it->ble.data, it->len);
    }
    b->tx_power_dbm = it->ble.tx_power != 127 ? it->ble.tx_power
                                              : rv_ble_tx_power(it->ble.data, it->len);
    b->primary_phy = it->ble.prim_phy;
    b->secondary_phy = it->ble.sec_phy;
    return true;
}

static void pipeline_task(void *arg)
{
    for (;;) {
        if (xQueueReceive(s_queue, &s_item, portMAX_DELAY) != pdTRUE || !s_running) {
            continue;
        }
        s_env = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
        s_env.which_payload = retrovision_v1_Envelope_observation_tag;
        retrovision_v1_Observation *obs = &s_env.payload.observation;

        bool emit = s_item.kind == RV_RAW_WIFI ? build_wifi(&s_item, obs) : build_ble(&s_item, obs);
        if (!emit) {
            continue;
        }
        if (!rv_link_send(&s_env, pdMS_TO_TICKS(20))) {
            s_drop_link++;
        } else if (s_item.kind == RV_RAW_WIFI) {
            s_wifi_sent++;
        } else {
            s_ble_sent++;
        }
    }
}

void rv_capture_init(void)
{
    s_queue = xQueueCreate(QUEUE_DEPTH, sizeof(rv_raw_item_t));
    configASSERT(s_queue);
    rv_dedup_init(&s_dedup);
    rv_cfg_defaults(&s_cfg);
    xTaskCreatePinnedToCore(pipeline_task, "rv_pipeline", 4096, NULL, 8, NULL, tskNO_AFFINITY);
    rv_wifi_sniffer_init();
    rv_ble_scanner_init();
}

void rv_capture_start(const rv_cfg_t *cfg)
{
    rv_capture_stop();
    s_cfg = *cfg;
    rv_dedup_clear(&s_dedup);
    s_running = true;
    if (s_cfg.wifi_enabled) {
        rv_wifi_sniffer_start(&s_cfg);
    }
    if (s_cfg.ble_enabled) {
        rv_ble_scanner_start(&s_cfg);
    }
    ESP_LOGI(TAG, "capture started (wifi=%d ble=%d hop=%u)", s_cfg.wifi_enabled,
             s_cfg.ble_enabled, s_cfg.hop_count);
}

void rv_capture_stop(void)
{
    if (!s_running) {
        return;
    }
    s_running = false;
    rv_wifi_sniffer_stop();
    rv_ble_scanner_stop();
    xQueueReset(s_queue);
    ESP_LOGI(TAG, "capture stopped");
}

void rv_capture_stats(rv_capture_stats_t *out)
{
    out->wifi_seen = g_rv_wifi_seen;
    out->ble_seen = g_rv_ble_seen;
    out->wifi_sent = s_wifi_sent;
    out->ble_sent = s_ble_sent;
    out->dropped = s_drop_q_wifi + s_drop_q_ble + s_drop_link;
}
