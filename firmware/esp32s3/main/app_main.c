// Retrovision probe firmware: ESP32-S3 (Seeed Studio XIAO ESP32-S3).
//
// Data flow:
//   Wi-Fi promiscuous cb ─┐
//                         ├─> raw queue ─> pipeline (parse, dedup, encode) ─┐
//   NimBLE GAP events ────┘                                                  ├─> USB-Serial-JTAG
//   session (hello, status, acks, time sync) ────────────────────────────────┘
//
// See docs/protocol.md for the wire protocol.
#include "capture.h"
#include "esp_log.h"
#include "link.h"
#include "log_forward.h"
#include "nvs_flash.h"
#include "session.h"

static const char *TAG = "main";

void app_main(void)
{
    // NVS holds RF calibration data for the Wi-Fi and BT PHY.
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    rv_link_init(rv_session_on_envelope);
    rv_log_forward_init();
    rv_capture_init();   // radios initialised but idle until the handshake
    rv_session_init();
    ESP_LOGI(TAG, "%s up, waiting for host", RV_PROBE_TYPE);
}
