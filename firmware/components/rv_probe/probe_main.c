// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Retrovision probe firmware, shared by every board (ESP32-S3 over native USB, classic ESP32 over UART).
//
// Data flow:
//   Wi-Fi promiscuous cb ─┐
//                         ├─> raw queue ─> pipeline (parse, dedup, encode) ─┐
//   NimBLE GAP events ────┘                                                  ├─> link (USB-Serial-JTAG or UART0)
//   session (hello, status, acks, time sync) ────────────────────────────────┘
//
// See docs/protocol.md for the wire protocol.
#include "rv_probe.h"
#include "capture.h"
#include "esp_log.h"
#include "link.h"
#include "link_cfg.h"
#include "log_forward.h"
#include "nvs_flash.h"
#include "session.h"

static const char *TAG = "main";

void rv_probe_start(void)
{
    // NVS holds RF calibration data for the Wi-Fi and BT PHY.
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    rv_link_cfg_load();
    rv_link_init(rv_session_on_envelope);
    rv_log_forward_init();
    rv_capture_init();   // radios initialised but idle until the handshake
    rv_session_init();
    ESP_LOGI(TAG, "%s up, waiting for host", RV_PROBE_TYPE);
}
