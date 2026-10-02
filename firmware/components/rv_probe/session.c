#include "session.h"

#include <stdio.h>
#include <string.h>

#include "capture.h"
#include "config.h"
#include "driver/gpio.h"
#include "soc/soc_caps.h"
#if SOC_TEMP_SENSOR_SUPPORTED
#include "driver/temperature_sensor.h"
#endif
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "link.h"
#include "rv_framing.h"
#if !CONFIG_IDF_TARGET_ESP32
#include "soc/rtc_cntl_reg.h"
#endif
#include "soc/soc.h"
#include "wifi_sniffer.h"

static const char *TAG = "session";

#if CONFIG_IDF_TARGET_ESP32
// NodeMCU-32S / most ESP32 DevKits: blue LED on GPIO2, active high (harmless on boards without it).
#define LED_GPIO GPIO_NUM_2
#define LED_ON 1
#elif CONFIG_IDF_TARGET_ESP32C5
// ESP32-C5-DevKitC-1 / Waveshare: the status LED is an addressable RGB (WS2812) on GPIO27,
// which needs the led_strip (RMT) driver, not gpio_set_level. Left undriven for now; the
// LED on/off config still applies (as a no-op) and the host shows state. TODO: WS2812 driver.
#define LED_NONE 1
#else
// XIAO ESP32-S3 user LED (orange), active low.
#define LED_GPIO GPIO_NUM_21
#define LED_ON 0
#endif
#define HELLO_PERIOD_MS 2000
#define HELLO_REJECTED_PERIOD_MS 10000
#define USB_GONE_MS 1000

typedef enum { ST_HELLO, ST_REJECTED, ST_ACTIVE } state_t;

static volatile state_t s_state = ST_HELLO;
static uint32_t s_boot_id;
static SemaphoreHandle_t s_lock;       // serialises state changes (rx task vs session task)
static rv_cfg_t s_cfg;
#if SOC_TEMP_SENSOR_SUPPORTED
static temperature_sensor_handle_t s_tsens;
#endif

// Envelopes built by the session. Two, because the rx task (acks) and the
// session task (hello/status) run concurrently.
static retrovision_v1_Envelope s_env_rx;
static retrovision_v1_Envelope s_env_task;

// ---------------------------------------------------------------------------

static void send_hello(void)
{
    retrovision_v1_Envelope *e = &s_env_task;
    *e = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
    e->which_payload = retrovision_v1_Envelope_hello_tag;
    retrovision_v1_Hello *h = &e->payload.hello;
    h->protocol_major = RV_PROTOCOL_MAJOR;
    h->protocol_minor = RV_PROTOCOL_MINOR;
    strlcpy(h->probe_type, RV_PROBE_TYPE, sizeof h->probe_type);
    strlcpy(h->firmware_version, esp_app_get_description()->version, sizeof h->firmware_version);
    uint8_t mac[6];
    if (esp_efuse_mac_get_default(mac) == ESP_OK) {
        h->hardware_id.size = 6;
        memcpy(h->hardware_id.bytes, mac, 6);
    }
    h->boot_id = s_boot_id;
    const retrovision_v1_Capability caps[] = {
        retrovision_v1_Capability_CAPABILITY_WIFI_PROBE_REQ,
        retrovision_v1_Capability_CAPABILITY_WIFI_BEACON,
        retrovision_v1_Capability_CAPABILITY_WIFI_DATA,
        retrovision_v1_Capability_CAPABILITY_WIFI_RAW_IES,
        retrovision_v1_Capability_CAPABILITY_BLE_ADV,
#if CONFIG_BT_NIMBLE_EXT_ADV
        retrovision_v1_Capability_CAPABILITY_BLE_EXT_ADV,
#endif
        retrovision_v1_Capability_CAPABILITY_BLE_ACTIVE_SCAN,
#ifdef RV_HAS_5GHZ
        retrovision_v1_Capability_CAPABILITY_WIFI_5GHZ,
#endif
    };
    h->capabilities_count = sizeof caps / sizeof caps[0];
    memcpy(h->capabilities, caps, sizeof caps);
    h->max_rx_frame = RV_MAX_DECODED_FRAME;
    for (uint32_t ch = RV_CFG_MIN_CHANNEL; ch <= RV_CFG_MAX_CHANNEL; ch++) {
        h->supported_wifi_channels[h->supported_wifi_channels_count++] = ch;
    }
    for (uint8_t i = 0; i < rv_cfg_5ghz_channel_count &&
         h->supported_wifi_channels_count < (sizeof h->supported_wifi_channels / sizeof h->supported_wifi_channels[0]);
         i++) {
        h->supported_wifi_channels[h->supported_wifi_channels_count++] = rv_cfg_5ghz_channels[i];
    }
    rv_link_send(e, pdMS_TO_TICKS(50));
}

static void fill_status(retrovision_v1_Envelope *e)
{
    *e = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
    e->which_payload = retrovision_v1_Envelope_status_tag;
    retrovision_v1_Status *s = &e->payload.status;
    rv_capture_stats_t st;
    rv_capture_stats(&st);
    s->probe_ts_us = (uint64_t)esp_timer_get_time();
    s->free_heap_bytes = esp_get_free_heap_size();
    s->min_free_heap_bytes = esp_get_minimum_free_heap_size();
#if SOC_TEMP_SENSOR_SUPPORTED
    float t = 0;
    if (s_tsens && temperature_sensor_get_celsius(s_tsens, &t) == ESP_OK) {
        s->chip_temp_c = t;
    }
#endif
    s->current_wifi_channel = rv_wifi_sniffer_channel();
    s->wifi_frames_seen = st.wifi_seen;
    s->wifi_obs_sent = st.wifi_sent;
    s->ble_adv_seen = st.ble_seen;
    s->ble_obs_sent = st.ble_sent;
    s->obs_dropped = st.dropped;
    s->rx_frames_bad = rv_link_rx_bad();
}

static void send_ack(uint32_t command_seq, retrovision_v1_AckResult res, const char *msg)
{
    retrovision_v1_Envelope *e = &s_env_rx;
    *e = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
    e->which_payload = retrovision_v1_Envelope_command_ack_tag;
    e->payload.command_ack.command_seq = command_seq;
    e->payload.command_ack.result = res;
    if (msg) {
        strlcpy(e->payload.command_ack.message, msg, sizeof e->payload.command_ack.message);
    }
    rv_link_send(e, pdMS_TO_TICKS(100));
}

// ---------------------------------------------------------------------------

static void go_idle(state_t st)
{
    rv_capture_stop();
    s_state = st;
}

static retrovision_v1_AckResult apply_config(const retrovision_v1_Config *pb, char *msg, size_t cap)
{
    rv_cfg_t next;
    retrovision_v1_AckResult r = rv_cfg_from_pb(pb, &next, msg, cap);
    s_cfg = next;
    rv_capture_start(&s_cfg);
    return r;
}

static void on_hello_ack(uint32_t seq, const retrovision_v1_HelloAck *a)
{
    if (a->boot_id != s_boot_id) {
        ESP_LOGW(TAG, "stale HelloAck (boot_id %08lx)", (unsigned long)a->boot_id);
        return;
    }
    if (!a->accepted) {
        ESP_LOGW(TAG, "rejected by host: %s", a->reject_reason);
        go_idle(ST_REJECTED);
        return;
    }
    if (a->protocol_major != RV_PROTOCOL_MAJOR) {
        ESP_LOGE(TAG, "host speaks protocol %lu.x", (unsigned long)a->protocol_major);
        go_idle(ST_REJECTED);
        return;
    }
    char msg[96] = "";
    retrovision_v1_AckResult r = retrovision_v1_AckResult_ACK_RESULT_OK;
    if (a->has_config) {
        r = apply_config(&a->config, msg, sizeof msg);
    } else {
        rv_cfg_defaults(&s_cfg);
        rv_capture_start(&s_cfg);
    }
    s_state = ST_ACTIVE;
    // Ack the HelloAck's initial config like a SetConfig (protocol §5).
    send_ack(seq, r, msg[0] ? msg : NULL);
    ESP_LOGI(TAG, "session active");
}

static void reboot(bool into_bootloader)
{
    rv_capture_stop();
    rv_link_flush(pdMS_TO_TICKS(200));
#if !CONFIG_IDF_TARGET_ESP32
    if (into_bootloader) {
        // Next reset boots the ROM download mode (esptool / web flasher).
        REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
    }
#else
    // The classic ESP32 can only enter download mode through the GPIO0 strap:
    // the host does that with DTR/RTS (auto-reset circuit) after this plain reboot.
    (void)into_bootloader;
#endif
    esp_restart();
}

static void on_command(uint32_t seq, const retrovision_v1_Command *c)
{
    char msg[96] = "";
    switch (c->which_kind) {
    case retrovision_v1_Command_set_config_tag: {
        retrovision_v1_AckResult r = apply_config(&c->kind.set_config, msg, sizeof msg);
        send_ack(seq, r, msg[0] ? msg : NULL);
        break;
    }
    case retrovision_v1_Command_time_sync_tag: {
        // Answer as fast as possible; no ack, the response is the ack.
        retrovision_v1_Envelope *e = &s_env_rx;
        *e = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
        e->which_payload = retrovision_v1_Envelope_time_sync_response_tag;
        e->payload.time_sync_response.host_t1_us = c->kind.time_sync.host_t1_us;
        e->payload.time_sync_response.probe_t2_us = (uint64_t)esp_timer_get_time();
        rv_link_send(e, pdMS_TO_TICKS(50));
        break;
    }
    case retrovision_v1_Command_get_status_tag:
        fill_status(&s_env_rx);
        rv_link_send(&s_env_rx, pdMS_TO_TICKS(100));
        break;
    case retrovision_v1_Command_reboot_tag:
        send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_OK, NULL);
        reboot(c->kind.reboot.into_bootloader);
        break;
    default:
        send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_UNSUPPORTED, "unknown command");
        break;
    }
}

void rv_session_on_envelope(const retrovision_v1_Envelope *env)
{
    if (s_lock == NULL) {
        return; // frame arrived before rv_session_init()
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    switch (env->which_payload) {
    case retrovision_v1_Envelope_hello_ack_tag:
        on_hello_ack(env->seq, &env->payload.hello_ack);
        break;
    case retrovision_v1_Envelope_command_tag:
        if (s_state == ST_ACTIVE) {
            on_command(env->seq, &env->payload.command);
        } else if (env->payload.command.which_kind == retrovision_v1_Command_time_sync_tag ||
                   env->payload.command.which_kind == retrovision_v1_Command_reboot_tag) {
            // Allowed before the handshake: lets a flasher reboot a probe
            // whose protocol the host does not speak.
            on_command(env->seq, &env->payload.command);
        } else {
            send_ack(env->seq, retrovision_v1_AckResult_ACK_RESULT_ERROR, "no session");
        }
        break;
    default:
        // Probe->host payloads echoed back, or unknown: ignore.
        break;
    }
    xSemaphoreGive(s_lock);
}

// ---------------------------------------------------------------------------

static void session_task(void *arg)
{
    int64_t last_hello = -HELLO_REJECTED_PERIOD_MS * 1000LL;
    int64_t last_status = 0;
    int64_t usb_gone_since = 0;
    bool led = false;

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(100));
        const int64_t now = esp_timer_get_time();

        xSemaphoreTake(s_lock, portMAX_DELAY);

        // Host detached: stop capturing and restart the handshake on return.
        if (!rv_link_host_connected()) {
            if (usb_gone_since == 0) {
                usb_gone_since = now;
            } else if (s_state != ST_HELLO && now - usb_gone_since > USB_GONE_MS * 1000LL) {
                ESP_LOGI(TAG, "USB host gone, back to handshake");
                go_idle(ST_HELLO);
            }
        } else {
            usb_gone_since = 0;
        }

        switch (s_state) {
        case ST_HELLO:
        case ST_REJECTED: {
            const int64_t period =
                (s_state == ST_HELLO ? HELLO_PERIOD_MS : HELLO_REJECTED_PERIOD_MS) * 1000LL;
            if (now - last_hello >= period) {
                send_hello();
                last_hello = now;
            }
            // Blink: 1 Hz waiting for host, 0.2 Hz rejected.
            led = ((now / 500000) % (s_state == ST_HELLO ? 2 : 10)) == 0;
            break;
        }
        case ST_ACTIVE:
            if (s_cfg.status_interval_s &&
                now - last_status >= (int64_t)s_cfg.status_interval_s * 1000000LL) {
                fill_status(&s_env_task);
                rv_link_send(&s_env_task, pdMS_TO_TICKS(100));
                last_status = now;
            }
            led = true;
            break;
        }
        xSemaphoreGive(s_lock);
#ifndef LED_NONE
        gpio_set_level(LED_GPIO, (led && !s_cfg.led_off) ? LED_ON : !LED_ON);
#endif
    }
}

void rv_session_init(void)
{
    s_boot_id = esp_random();
    if (s_boot_id == 0) {
        s_boot_id = 1;
    }
    s_lock = xSemaphoreCreateMutex();
    rv_cfg_defaults(&s_cfg);

#ifndef LED_NONE
    gpio_reset_pin(LED_GPIO);
    gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
    gpio_set_level(LED_GPIO, !LED_ON);
#endif

#if SOC_TEMP_SENSOR_SUPPORTED
    temperature_sensor_config_t tcfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(-10, 80);
    if (temperature_sensor_install(&tcfg, &s_tsens) == ESP_OK) {
        temperature_sensor_enable(s_tsens);
    } else {
        s_tsens = NULL;
    }
#endif

    xTaskCreatePinnedToCore(session_task, "rv_session", 4096, NULL, 7, NULL, tskNO_AFFINITY);
}
