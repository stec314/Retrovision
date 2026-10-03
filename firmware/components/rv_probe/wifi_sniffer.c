// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#include "wifi_sniffer.h"

#include <string.h>

#include "capture.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "rv_wifi.h"

static const char *TAG = "wifi";

#define FCS_LEN 4

static TaskHandle_t s_hop_task;
static volatile bool s_active;
static volatile uint8_t s_channel;
static const rv_cfg_t *s_cfg;

// The promiscuous callback runs in the Wi-Fi driver task (not an ISR), so one
// static scratch item is enough (and keeps 0.5 KB off that task's stack).
static rv_raw_item_t s_item;

static void promisc_cb(void *buf, wifi_promiscuous_pkt_type_t type)
{
    if (!s_active || (type != WIFI_PKT_MGMT && type != WIFI_PKT_DATA)) {
        return;
    }
    const wifi_promiscuous_pkt_t *pkt = buf;
    g_rv_wifi_seen++;

    int len = (int)pkt->rx_ctrl.sig_len - FCS_LEN;
    if (len < 24) {
        return;
    }
    const rv_wifi_type_t t = rv_wifi_classify(pkt->payload[0]);
    if (t == RV_WIFI_UNSPECIFIED || !(s_cfg->wifi_type_mask & (1u << t))) {
        return;
    }
    if (pkt->rx_ctrl.rssi < s_cfg->wifi_min_rssi) {
        return;
    }

    s_item.kind = RV_RAW_WIFI;
    s_item.ts_us = esp_timer_get_time();
    s_item.rssi = (int8_t)pkt->rx_ctrl.rssi;
    s_item.channel = pkt->rx_ctrl.channel;
    s_item.cut = len > RV_RAW_WIFI_MAX;
    s_item.len = (uint16_t)(s_item.cut ? RV_RAW_WIFI_MAX : len);
    memcpy(s_item.wifi.frame, pkt->payload, s_item.len);
    rv_capture_submit(&s_item);
}

static uint32_t s_tune_fail;

static void hop_task(void *arg)
{
    size_t i = 0;
    for (;;) {
        if (!s_active) {
            ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
            i = 0;
            continue;
        }
        const rv_hop_t h = s_cfg->hop[i % s_cfg->hop_count];
        if (esp_wifi_set_channel(h.channel, WIFI_SECOND_CHAN_NONE) == ESP_OK) {
            s_channel = h.channel;
        } else if (s_tune_fail++ < 5) {
            ESP_LOGW(TAG, "cannot tune channel %u", (unsigned)h.channel);
        }
        i++;
        // Wake early on stop so reconfiguration is quick.
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(h.dwell_ms));
    }
}

void rv_wifi_sniffer_init(void)
{
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    init.nvs_enable = false;
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    // NULL mode: no STA/AP interface, so the probe never transmits.
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
    // EU regulatory domain, channels 1..13 (receive-only anyway).
    wifi_country_t country = {
        .cc = "EU",
        .schan = RV_CFG_MIN_CHANNEL,
        .nchan = RV_CFG_MAX_CHANNEL - RV_CFG_MIN_CHANNEL + 1,
        .policy = WIFI_COUNTRY_POLICY_MANUAL,
    };
    ESP_ERROR_CHECK(esp_wifi_set_country(&country));
    ESP_ERROR_CHECK(esp_wifi_start());
#if CONFIG_SOC_WIFI_SUPPORT_5G
    // Dual band: let channel changes move between 2.4 and 5 GHz (the hop plan mixes both).
    esp_err_t bm = esp_wifi_set_band_mode(WIFI_BAND_MODE_AUTO);
    ESP_LOGI(TAG, "band mode auto: %s", esp_err_to_name(bm));
#endif
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_rx_cb(promisc_cb));
    xTaskCreatePinnedToCore(hop_task, "rv_hop", 3072, NULL, 9, &s_hop_task, tskNO_AFFINITY);
}

void rv_wifi_sniffer_start(const rv_cfg_t *cfg)
{
    s_cfg = cfg;
    wifi_promiscuous_filter_t filter = {.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT};
    if (cfg->wifi_type_mask & (1u << RV_WIFI_DATA)) {
        filter.filter_mask |= WIFI_PROMIS_FILTER_MASK_DATA;
    }
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&filter));
    s_active = true;
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true));
    xTaskNotifyGive(s_hop_task);
    ESP_LOGI(TAG, "sniffing, %u hops", cfg->hop_count);
}

void rv_wifi_sniffer_stop(void)
{
    if (!s_active) {
        return;
    }
    s_active = false;
    esp_wifi_set_promiscuous(false);
    xTaskNotifyGive(s_hop_task);
}

uint8_t rv_wifi_sniffer_channel(void)
{
    return s_channel;
}
