/*
 * Retrovision field-test target.
 *
 * A device you carry (or give to a friend) on purpose, to check that the probe hears it and that
 * the analysis flags it once it has been with you at enough stops. It is easy to recognise and
 * never pretends to be anything else:
 *   - BLE: non-connectable advertising every 100 ms, public (fixed) address, name RV-TARGET-XXXX;
 *   - Wi-Fi: an access point named RV-TARGET-XXXX on channel 6 (WPA2 with a random password:
 *     nobody can join it).
 * XXXX are the last two bytes of the chip's MAC, so two targets do not collide.
 * The LED blinks once every 2 s while it runs.
 */
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_random.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include "sdkconfig.h"

#define WIFI_CHANNEL 6
#define ADV_INTERVAL_MS 100

#if CONFIG_IDF_TARGET_ESP32S3
#define LED_GPIO GPIO_NUM_21 /* XIAO ESP32-S3 user LED, active low */
#define LED_ON 0
#else
#define LED_GPIO GPIO_NUM_2 /* NodeMCU-32S / DevKitC, active high */
#define LED_ON 1
#endif

static const char *TAG = "rv_target";
static char s_name[20];

/* ---------------------------------------------------------------- Wi-Fi */

static void wifi_start(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));

    wifi_config_t ap = {0};
    size_t n = strlen(s_name);
    memcpy(ap.ap.ssid, s_name, n);
    ap.ap.ssid_len = n;
    ap.ap.channel = WIFI_CHANNEL;
    ap.ap.authmode = WIFI_AUTH_WPA2_PSK;
    ap.ap.max_connection = 1;
    ap.ap.beacon_interval = 100;
    /* Random 32-hex-char password, never stored or shown: the network exists to be seen, not joined. */
    for (int i = 0; i < 16; i++) {
        snprintf((char *)ap.ap.password + 2 * i, 3, "%02x", (unsigned)(esp_random() & 0xff));
    }

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "Wi-Fi AP \"%s\" on channel %d", s_name, WIFI_CHANNEL);
}

/* ---------------------------------------------------------------- BLE */

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)event;
    (void)arg;
    return 0;
}

static void ble_advertise(void)
{
    uint8_t own_addr_type;
    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "no BLE address: %d", rc);
        return;
    }

    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)s_name;
    fields.name_len = strlen(s_name);
    fields.name_is_complete = 1;
    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv fields: %d", rc);
        return;
    }

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_NON;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    params.itvl_min = BLE_GAP_ADV_ITVL_MS(ADV_INTERVAL_MS);
    params.itvl_max = BLE_GAP_ADV_ITVL_MS(ADV_INTERVAL_MS);
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv start: %d", rc);
        return;
    }
    ESP_LOGI(TAG, "BLE advertising \"%s\" every %d ms (address type %d)", s_name, ADV_INTERVAL_MS, own_addr_type);
}

static void ble_on_reset(int reason)
{
    ESP_LOGW(TAG, "BLE host reset: %d", reason);
}

static void ble_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static void ble_start(void)
{
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = ble_advertise;
    ble_hs_cfg.reset_cb = ble_on_reset;
    nimble_port_freertos_init(ble_host_task);
}

/* ---------------------------------------------------------------- main */

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    uint8_t mac[6];
    ESP_ERROR_CHECK(esp_efuse_mac_get_default(mac));
    snprintf(s_name, sizeof(s_name), "RV-TARGET-%02X%02X", mac[4], mac[5]);
    ESP_LOGI(TAG, "Retrovision field-test target %s", s_name);

    wifi_start();
    ble_start();

    gpio_reset_pin(LED_GPIO);
    gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
    for (;;) {
        gpio_set_level(LED_GPIO, LED_ON);
        vTaskDelay(pdMS_TO_TICKS(60));
        gpio_set_level(LED_GPIO, !LED_ON);
        vTaskDelay(pdMS_TO_TICKS(1940));
    }
}
