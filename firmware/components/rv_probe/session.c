// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#include "session.h"

#include <stdio.h>
#include <string.h>

#include "capture.h"
#include "ble_scanner.h"
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
#include "link_cfg.h"
#include "rv_framing.h"
#if CONFIG_IDF_TARGET_ESP32S3 || CONFIG_IDF_TARGET_ESP32S2
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
// driven over RMT below. The red power LED is wired to 3.3 V and cannot be switched off.
#define LED_WS2812_GPIO 27
#include "driver/rmt_tx.h"
static rmt_channel_handle_t s_led_chan;
static rmt_encoder_handle_t s_led_enc;
static int s_led_last = -1;

static void ws2812_init(void)
{
    const rmt_tx_channel_config_t c = {
        .gpio_num = LED_WS2812_GPIO,
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10000000, // 0.1 us ticks
        .mem_block_symbols = 48,
        .trans_queue_depth = 2,
    };
    // WS2812: 0 = 0.3 us high + 0.9 us low, 1 = 0.9 us high + 0.3 us low, MSB first, GRB order.
    const rmt_bytes_encoder_config_t e = {
        .bit0 = {.level0 = 1, .duration0 = 3, .level1 = 0, .duration1 = 9},
        .bit1 = {.level0 = 1, .duration0 = 9, .level1 = 0, .duration1 = 3},
        .flags.msb_first = 1,
    };
    if (rmt_new_tx_channel(&c, &s_led_chan) != ESP_OK || rmt_new_bytes_encoder(&e, &s_led_enc) != ESP_OK ||
        rmt_enable(s_led_chan) != ESP_OK) {
        ESP_LOGW(TAG, "status LED (WS2812) not available");
        s_led_chan = NULL;
    }
}

// on = a dim colour (bright LEDs give a probe away); off = dark. Sent only when it changes.
static void ws2812_set(bool on, bool waiting)
{
    const int want = on ? (waiting ? 2 : 1) : 0;
    if (!s_led_chan || want == s_led_last) {
        return;
    }
    s_led_last = want;
    // GRB: dim green when capturing, dim blue while waiting for the phone.
    uint8_t grb[3] = {0, 0, 0};
    if (want == 1) grb[0] = 6;
    if (want == 2) grb[2] = 6;
    const rmt_transmit_config_t t = {.loop_count = 0};
    if (rmt_transmit(s_led_chan, s_led_enc, grb, sizeof grb, &t) == ESP_OK) {
        rmt_tx_wait_all_done(s_led_chan, 20);
    }
}
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
// Transport the current handshake/session runs on. USB is preferred whenever the cable is
// connected; BLE is used only when configured and no cable is present.
static rv_transport_t s_link = RV_T_USB;
// Challenge sent in the last wireless Hello; a wireless HelloAck must answer it with a valid MAC.
static uint8_t s_nonce[16];
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
#if CONFIG_BT_NIMBLE_ENABLED
        retrovision_v1_Capability_CAPABILITY_LINK_BLE,
#endif
    };
    h->capabilities_count = sizeof caps / sizeof caps[0];
    memcpy(h->capabilities, caps, sizeof caps);
    h->max_rx_frame = RV_MAX_DECODED_FRAME;
    h->link = rv_link_kind(s_link);
    h->configured_link = rv_link_cfg()->mode;
    strlcpy(h->name, rv_link_cfg()->name, sizeof h->name);
    // On a wireless link the probe streams nothing until the host proves it knows the pairing
    // key: a fresh random challenge goes out with every Hello.
    if (s_link != RV_T_USB) {
        esp_fill_random(s_nonce, sizeof s_nonce);
        h->auth_nonce.size = sizeof s_nonce;
        memcpy(h->auth_nonce.bytes, s_nonce, sizeof s_nonce);
    }
    for (uint32_t ch = RV_CFG_MIN_CHANNEL; ch <= RV_CFG_MAX_CHANNEL; ch++) {
        h->supported_wifi_channels[h->supported_wifi_channels_count++] = ch;
    }
    for (uint8_t i = 0; i < rv_cfg_5ghz_channel_count &&
         h->supported_wifi_channels_count < (sizeof h->supported_wifi_channels / sizeof h->supported_wifi_channels[0]);
         i++) {
        h->supported_wifi_channels[h->supported_wifi_channels_count++] = rv_cfg_5ghz_channels[i];
    }
    if (s_link == RV_T_USB && !rv_link_connected(RV_T_USB)) {
        // Nobody has talked on the cable yet (a probe set up for BLE does not assume a host at
        // boot). Announce anyway, or a phone plugged in now would wait for a Hello forever while
        // we wait for it to speak: on a classic ESP32 every "reboot" kick from the app restarted
        // that wait.
        if (rv_link_usb_present()) {
            rv_link_send_to_unchecked(RV_T_USB, e, pdMS_TO_TICKS(20));
        }
        return;
    }
    rv_link_send_to(s_link, e, pdMS_TO_TICKS(50));
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
    s->link = rv_link_kind(s_link);
    if (s_link == RV_T_BLE) {
        s->link_rssi = rv_link_ble_rssi();
    }
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
    if (rv_link_cfg()->mode == retrovision_v1_LinkKind_LINK_KIND_BLE) {
        next.ble_enabled = false; // the Bluetooth radio carries the link, it cannot also scan
    }
    s_cfg = next;
    rv_capture_start(&s_cfg);
    return r;
}

static void on_hello_ack(uint32_t seq, const retrovision_v1_HelloAck *a, rv_transport_t from)
{
    if (from != s_link) {
        return; // ack for a transport we are not handshaking on
    }
    if (a->boot_id != s_boot_id) {
        ESP_LOGW(TAG, "stale HelloAck (boot_id %08lx)", (unsigned long)a->boot_id);
        return;
    }
    // Wireless links must authenticate: the host answers the Hello challenge with
    // HMAC-SHA256(pairing key, ...). Without a valid MAC the probe streams nothing.
    if (s_link != RV_T_USB && a->accepted) {
        if (a->auth_mac.size != 32 || !rv_link_auth_check(s_nonce, s_boot_id, a->auth_mac.bytes, a->auth_mac.size)) {
            ESP_LOGW(TAG, "wireless auth failed; not starting session");
            // Tell the host why, so it can ask for a re-pair instead of retrying blindly.
            send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_INVALID, "auth failed: pairing key mismatch");
            go_idle(ST_HELLO); // retry with a fresh challenge
            return;
        }
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
#if CONFIG_IDF_TARGET_ESP32S3 || CONFIG_IDF_TARGET_ESP32S2
    if (into_bootloader) {
        // Next reset boots the ROM download mode (esptool / web flasher).
        REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
    }
#else
    // Classic ESP32 and C5: enter download mode via the auto-reset circuit
    // (DTR/RTS) or the BOOT strap; this is a plain reboot.
    (void)into_bootloader;
#endif
    esp_restart();
}

static void on_command(uint32_t seq, const retrovision_v1_Command *c, rv_transport_t from)
{
    char msg[96] = "";
    switch (c->which_kind) {
    case retrovision_v1_Command_set_link_tag: {
        // Pairing stores the key and the mode; it requires physical access, so USB only.
        if (from != RV_T_USB) {
            send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_INVALID, "SetLink only over USB");
            break;
        }
        if (!rv_link_cfg_save(&c->kind.set_link, msg, sizeof msg)) {
            send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_INVALID, msg[0] ? msg : "invalid link");
            break;
        }
        send_ack(seq, retrovision_v1_AckResult_ACK_RESULT_OK, "rebooting to apply link");
        reboot(false);
        break;
    }
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

void rv_session_on_envelope(const retrovision_v1_Envelope *env, rv_transport_t from)
{
    if (s_lock == NULL) {
        return; // frame arrived before rv_session_init()
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    switch (env->which_payload) {
    case retrovision_v1_Envelope_hello_ack_tag:
        on_hello_ack(env->seq, &env->payload.hello_ack, from);
        break;
    case retrovision_v1_Envelope_command_tag:
        if (s_state == ST_ACTIVE) {
            on_command(env->seq, &env->payload.command, from);
        } else if (env->payload.command.which_kind == retrovision_v1_Command_time_sync_tag ||
                   env->payload.command.which_kind == retrovision_v1_Command_reboot_tag) {
            // Allowed before the handshake: lets a flasher reboot a probe
            // whose protocol the host does not speak.
            on_command(env->seq, &env->payload.command, from);
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
    int64_t last_ble_tick = 0;
    int64_t last_thermal = 0;
    int thermal = 0;
    int64_t last_ble_report = 0;
    bool was_active = false;
    bool led = false;

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(100));
        const int64_t now = esp_timer_get_time();

        xSemaphoreTake(s_lock, portMAX_DELAY);

        // Pick the transport: the cable wins whenever it is connected, else BLE if it is.
        // Plugging or unplugging the cable restarts the handshake on the new transport.
        const bool usb = rv_link_connected(RV_T_USB);
        const bool ble = rv_link_connected(RV_T_BLE);
        // With no host anywhere, fall back to the cable: Hellos then reach a phone that plugs in
        // later (a dropped BLE host has to reconnect and handshake again anyway).
        const rv_transport_t want = usb ? RV_T_USB : (ble ? RV_T_BLE : RV_T_USB);
        if (want != s_link && (usb || ble || s_link != RV_T_USB)) {
            ESP_LOGI(TAG, "link switch %d -> %d", s_link, want);
            s_link = want;
            rv_link_set_active(want);
            go_idle(ST_HELLO);
            usb_gone_since = 0;
        } else if (!usb && !ble) {
            // Host detached on every transport: restart the handshake after a short grace.
            if (usb_gone_since == 0) {
                usb_gone_since = now;
            } else if (s_state != ST_HELLO && now - usb_gone_since > USB_GONE_MS * 1000LL) {
                ESP_LOGI(TAG, "host gone, back to handshake");
                go_idle(ST_HELLO);
            }
        } else {
            usb_gone_since = 0;
        }
        rv_link_set_active(s_link);

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
        const bool active_now = s_state == ST_ACTIVE;
        xSemaphoreGive(s_lock);

        // Thermal throttle on the die temperature (it runs 20-30 °C above the air around it). BLE
        // scanning shares the radio with Wi-Fi and is what can give: half its window from 80 °C,
        // off from 88 °C, back to normal under 72 °C.
#if SOC_TEMP_SENSOR_SUPPORTED
        if (s_tsens && now - last_thermal >= 10000000LL) {
            last_thermal = now;
            float t = 0;
            if (temperature_sensor_get_celsius(s_tsens, &t) == ESP_OK) {
                int want = thermal;
                if (t >= 88.0f) {
                    want = 2;
                } else if (t >= 80.0f && thermal < 1) {
                    want = 1;
                } else if (t < 72.0f) {
                    want = 0;
                }
                if (want != thermal) {
                    thermal = want;
                    ESP_LOGW("thermal", "%.0f C: BLE scan %s", t, want == 0 ? "normal" : want == 1 ? "halved" : "paused");
                    rv_ble_scanner_set_throttle(want);
                }
            }
        }
#endif

        // BLE link mode: keep advertising alive, and tell the host over the cable how the
        // Bluetooth side is doing (it cannot see it otherwise when the phone does not find us).
        if (rv_link_cfg()->mode == retrovision_v1_LinkKind_LINK_KIND_BLE) {
            if (now - last_ble_tick >= 5000000LL) {
                last_ble_tick = now;
                rv_link_ble_tick();
            }
            if (active_now && s_link == RV_T_USB &&
                (!was_active || now - last_ble_report >= 30000000LL)) {
                last_ble_report = now;
                char line[160];
                rv_link_ble_report(line, sizeof line);
                ESP_LOGW("blelink", "%s", line);
            }
        }
        was_active = active_now;
#if defined(LED_WS2812_GPIO)
        ws2812_set(led && !s_cfg.led_off, s_state != ST_ACTIVE);
#elif !defined(LED_NONE)
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

#if defined(LED_WS2812_GPIO)
    ws2812_init();
    s_led_last = -1;
    ws2812_set(false, true); // start dark
#elif !defined(LED_NONE)
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
