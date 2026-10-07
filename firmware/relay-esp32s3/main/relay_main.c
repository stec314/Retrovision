// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Retrovision BLE relay: a XIAO ESP32-S3 wired to a probe's UART, carrying the probe's byte
// stream to the phone over BLE (Nordic UART Service layout, same as a probe's own BLE link).
//
//   probe TX ──> relay RX (GPIO44, D7)     probe RX <── relay TX (GPIO43, D6)     GND ── GND
//
// Why: a probe whose own Bluetooth radio carries the link cannot scan Bluetooth. With the relay
// the probe (typically the dual-band C5) keeps scanning Wi-Fi and BLE and only talks UART.
//
// What the relay does and does not do:
//   - It forwards whole frames (split at the 0x00 COBS delimiter). When BLE cannot keep up it
//     drops whole frames, never half of one; the phone sees the gap in Envelope.seq.
//   - It reads the probe's Hello only to learn the probe's name and advertise as "RV-<name>", so
//     the app finds it exactly as it would find the probe itself. The name is kept in NVS.
//   - It never sees the pairing key. The probe challenges the phone (HMAC) end to end; a relay
//     cannot make the probe stream to anyone who does not hold the key.
//   - On every BLE connect and disconnect it sends a UART break: the probe drops whatever session
//     it had and starts a fresh handshake at once.
//   - Every 60 s while a phone is connected it adds one Log frame (tag "relay", seq 0) with its
//     own counters, so link problems can be told apart from probe problems.
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/stream_buffer.h"
#include "freertos/task.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_hs_adv.h"
#include "host/ble_store.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "os/os_mbuf.h"
#include "pb_decode.h"
#include "pb_encode.h"
#include "retrovision.pb.h"
#include "rv_framing.h"
#include "sdkconfig.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

void ble_store_config_init(void);

static const char *TAG = "relay";

#define RELAY_UART UART_NUM_1
#define UART_TO_BLE_BUF 16384 // ~0.5 s of a busy probe; beyond that BLE is not keeping up anyway
#define BLE_TO_UART_BUF 2048

static const ble_uuid128_t NUS_SVC =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);
static const ble_uuid128_t NUS_RX =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);
static const ble_uuid128_t NUS_TX =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);

static uint16_t s_tx_handle;
static volatile uint16_t s_conn = BLE_HS_CONN_HANDLE_NONE;
static volatile bool s_subscribed;
static volatile uint16_t s_mtu = 23;
static uint8_t s_own_addr_type;
static volatile bool s_synced;
static volatile int64_t s_conn_since_us;
static volatile int64_t s_last_phone_rx_us;
static volatile int64_t s_last_probe_rx_us;

static StreamBufferHandle_t s_up;    // probe -> phone, whole frames only
static SemaphoreHandle_t s_up_lock;  // two producers: the UART task and the report
static StreamBufferHandle_t s_down;  // phone -> probe

// Name learned from the probe's Hello ("" until then: no advertising without a name).
static char s_name[25];
static SemaphoreHandle_t s_name_lock;
static volatile bool s_name_changed;

// Counters for the report.
static volatile uint32_t s_fwd_frames, s_drop_frames, s_bad_frames, s_conns, s_breaks;
static volatile int s_last_disc = -1;

static int gap_event(struct ble_gap_event *ev, void *arg);

// ---------------------------------------------------------------------------- LED

static void led_set(bool on)
{
#if CONFIG_RV_RELAY_LED_GPIO >= 0
#if CONFIG_RV_RELAY_LED_ACTIVE_LOW
    gpio_set_level(CONFIG_RV_RELAY_LED_GPIO, on ? 0 : 1);
#else
    gpio_set_level(CONFIG_RV_RELAY_LED_GPIO, on ? 1 : 0);
#endif
#else
    (void)on;
#endif
}

// ---------------------------------------------------------------------------- probe name

static void name_load(void)
{
    nvs_handle_t h;
    if (nvs_open("rv_relay", NVS_READONLY, &h) != ESP_OK) {
        return;
    }
    size_t n = sizeof s_name;
    if (nvs_get_str(h, "name", s_name, &n) != ESP_OK) {
        s_name[0] = 0;
    }
    nvs_close(h);
}

static void name_learn(const char *name)
{
    if (name[0] == 0) {
        return;
    }
    xSemaphoreTake(s_name_lock, portMAX_DELAY);
    const bool same = strncmp(name, s_name, sizeof s_name) == 0;
    if (!same) {
        strlcpy(s_name, name, sizeof s_name);
    }
    xSemaphoreGive(s_name_lock);
    if (same) {
        return;
    }
    ESP_LOGI(TAG, "probe name: %s", name);
    nvs_handle_t h;
    if (nvs_open("rv_relay", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_str(h, "name", name);
        nvs_commit(h);
        nvs_close(h);
    }
    s_name_changed = true; // the BLE task re-advertises under the new name
}

// ---------------------------------------------------------------------------- probe -> phone

static void push_frame(const uint8_t *frame, size_t len)
{
    if (s_conn == BLE_HS_CONN_HANDLE_NONE || !s_subscribed) {
        return; // nobody to forward to; the probe keeps sending Hellos until somebody answers
    }
    xSemaphoreTake(s_up_lock, portMAX_DELAY);
    if (xStreamBufferSpacesAvailable(s_up) >= len) {
        xStreamBufferSend(s_up, frame, len, 0);
        s_fwd_frames++;
    } else {
        s_drop_frames++;
    }
    xSemaphoreGive(s_up_lock);
}

// Peeks at a frame for the probe's Hello (name only). Everything else passes through untouched.
static rv_frame_decoder_t s_peek;
static retrovision_v1_Envelope s_peek_env;

static void peek_frame(const uint8_t *frame, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        const uint8_t *env;
        size_t n;
        rv_frame_result_t r = rv_frame_decoder_feed(&s_peek, frame[i], &env, &n);
        if (r == RV_FRAME_BAD) {
            s_bad_frames++;
        }
        if (r != RV_FRAME_OK) {
            continue;
        }
        s_peek_env = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
        pb_istream_t is = pb_istream_from_buffer(env, n);
        if (pb_decode(&is, retrovision_v1_Envelope_fields, &s_peek_env) &&
            s_peek_env.which_payload == retrovision_v1_Envelope_hello_tag) {
            name_learn(s_peek_env.payload.hello.name);
        }
    }
}

static void uart_rx_task(void *arg)
{
    static uint8_t frame[RV_FRAME_BUF_SIZE];
    size_t len = 0;
    bool overflow = false;
    uint8_t chunk[256];
    for (;;) {
        int n = uart_read_bytes(RELAY_UART, chunk, sizeof chunk, pdMS_TO_TICKS(20));
        if (n <= 0) {
            continue;
        }
        s_last_probe_rx_us = esp_timer_get_time();
        for (int i = 0; i < n; i++) {
            const uint8_t b = chunk[i];
            if (b != 0) {
                if (len < sizeof frame - 1) {
                    frame[len++] = b;
                } else {
                    overflow = true;
                }
                continue;
            }
            // Delimiter: one whole frame (or junk, e.g. the ROM banner at boot).
            if (!overflow && len > 0) {
                frame[len++] = 0;
                // Hellos are small; only frames that could be one are decoded.
                if (len < 400) {
                    peek_frame(frame, len);
                }
                push_frame(frame, len);
            } else if (overflow) {
                s_bad_frames++;
            }
            len = 0;
            overflow = false;
        }
    }
}

static void ble_tx_task(void *arg)
{
    static uint8_t buf[512];
    for (;;) {
        // Small waits let several frames share one notification when the MTU allows it.
        const uint16_t mtu = s_mtu;
        const size_t chunk_max = mtu > 3 ? (size_t)(mtu - 3) : 20;
        size_t n = xStreamBufferReceive(s_up, buf, chunk_max < sizeof buf ? chunk_max : sizeof buf, portMAX_DELAY);
        if (n == 0) {
            continue;
        }
        const int64_t start = esp_timer_get_time();
        for (;;) {
            const uint16_t conn = s_conn;
            if (conn == BLE_HS_CONN_HANDLE_NONE || !s_subscribed) {
                xStreamBufferReset(s_up); // stale data for a phone that left
                break;
            }
            struct os_mbuf *om = ble_hs_mbuf_from_flat(buf, n);
            int rc = om ? ble_gatts_notify_custom(conn, s_tx_handle, om) : BLE_HS_ENOMEM;
            if (rc == 0) {
                break;
            }
            if ((rc != BLE_HS_ENOMEM && rc != BLE_HS_EBUSY) || esp_timer_get_time() - start > 2000000) {
                s_drop_frames++; // a broken notification; the phone's decoder resyncs at the next 0x00
                break;
            }
            vTaskDelay(pdMS_TO_TICKS(5)); // controller buffers full: let them drain
        }
    }
}

// ---------------------------------------------------------------------------- phone -> probe

static void uart_tx_task(void *arg)
{
    uint8_t buf[256];
    for (;;) {
        size_t n = xStreamBufferReceive(s_down, buf, sizeof buf, portMAX_DELAY);
        if (n > 0) {
            uart_write_bytes(RELAY_UART, buf, n);
        }
    }
}

// Tells the probe "the phone connected/left": a break is out of band, it cannot be confused
// with frame bytes.
static void send_break(void)
{
    static const uint8_t zero = 0; // a delimiter: harmless to the probe's decoder
    uart_write_bytes_with_break(RELAY_UART, &zero, 1, 200);
    s_breaks++;
}

// ---------------------------------------------------------------------------- GATT

static int rx_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    uint8_t buf[256];
    const uint16_t total = OS_MBUF_PKTLEN(ctxt->om);
    uint16_t off = 0;
    s_last_phone_rx_us = esp_timer_get_time();
    while (off < total) {
        const uint16_t n = total - off > sizeof buf ? sizeof buf : total - off;
        if (os_mbuf_copydata(ctxt->om, off, n, buf) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        xStreamBufferSend(s_down, buf, n, 0); // never block the host task
        off += n;
    }
    return 0;
}

static int tx_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}

static const struct ble_gatt_svc_def s_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &NUS_SVC.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &NUS_RX.u,
                .access_cb = rx_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_ENC,
            },
            {
                .uuid = &NUS_TX.u,
                .access_cb = tx_access,
                .val_handle = &s_tx_handle,
                .flags = BLE_GATT_CHR_F_NOTIFY,
            },
            {0},
        },
    },
    {0},
};

static void advertise(void)
{
    if (!s_synced || s_conn != BLE_HS_CONN_HANDLE_NONE) {
        return;
    }
    char adv_name[32];
    xSemaphoreTake(s_name_lock, portMAX_DELAY);
    const bool known = s_name[0] != 0;
    snprintf(adv_name, sizeof adv_name, "RV-%s", s_name);
    xSemaphoreGive(s_name_lock);
    if (!known) {
        return; // wait for the probe's first Hello: an unnamed relay would match no paired probe
    }
    if (s_name_changed) {
        s_name_changed = false;
        if (ble_gap_adv_active()) {
            ble_gap_adv_stop();
        }
    }
    if (ble_gap_adv_active()) {
        return;
    }
    ble_svc_gap_device_name_set(adv_name);

    struct ble_hs_adv_fields f;
    memset(&f, 0, sizeof f);
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.uuids128 = &NUS_SVC;
    f.num_uuids128 = 1;
    f.uuids128_is_complete = 1;
    struct ble_hs_adv_fields rsp;
    memset(&rsp, 0, sizeof rsp);
    const size_t nl = strlen(adv_name);
    rsp.name = (const uint8_t *)adv_name;
    rsp.name_len = nl > 29 ? 29 : nl;
    rsp.name_is_complete = nl <= 29;
    ble_gap_adv_set_fields(&f);
    ble_gap_adv_rsp_set_fields(&rsp);

    struct ble_gap_adv_params p;
    memset(&p, 0, sizeof p);
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    p.itvl_min = BLE_GAP_ADV_ITVL_MS(200);
    p.itvl_max = BLE_GAP_ADV_ITVL_MS(300);
    int rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG, "adv start: %d", rc);
    } else {
        ESP_LOGI(TAG, "advertising as %s", adv_name);
    }
}

static int gap_event(struct ble_gap_event *ev, void *arg)
{
    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status == 0) {
            s_conn = ev->connect.conn_handle;
            s_subscribed = false;
            s_mtu = 23;
            s_conn_since_us = s_last_phone_rx_us = esp_timer_get_time();
            s_conns++;
            xStreamBufferReset(s_down);
            send_break(); // whatever the probe was doing, a new phone means a new handshake
            ESP_LOGI(TAG, "phone connected");
        } else {
            advertise();
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "phone disconnected (%d)", ev->disconnect.reason);
        s_last_disc = ev->disconnect.reason;
        s_conn = BLE_HS_CONN_HANDLE_NONE;
        s_subscribed = false;
        send_break();
        advertise();
        return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (ev->subscribe.attr_handle == s_tx_handle) {
            s_subscribed = ev->subscribe.cur_notify;
        }
        return 0;
    case BLE_GAP_EVENT_MTU:
        s_mtu = ev->mtu.value;
        return 0;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        // The phone forgot the bond: delete ours and pair again rather than fail.
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(ev->repeat_pairing.conn_handle, &d) == 0) {
            ble_store_util_delete_peer(&d.peer_id_addr);
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    default:
        return 0;
    }
}

static void on_sync(void)
{
    if (ble_hs_id_infer_auto(0, &s_own_addr_type) != 0) {
        s_own_addr_type = BLE_OWN_ADDR_PUBLIC;
    }
    s_synced = true;
    advertise();
}

static void on_reset(int reason)
{
    s_synced = false;
    ESP_LOGW(TAG, "BLE host reset: %d", reason);
}

static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

// ---------------------------------------------------------------------------- report

static void send_report(void)
{
    static retrovision_v1_Envelope env;
    static uint8_t pb[256];
    static uint8_t frame[320];
    env = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
    env.seq = 0; // not the probe's sequence: the app does not count it as a gap
    env.which_payload = retrovision_v1_Envelope_log_tag;
    retrovision_v1_Log *log = &env.payload.log;
    log->probe_ts_us = (uint64_t)esp_timer_get_time();
    log->level = retrovision_v1_LogLevel_LOG_LEVEL_INFO;
    strlcpy(log->tag, "relay", sizeof log->tag);
    int8_t rssi = 0;
    const uint16_t conn = s_conn;
    if (conn != BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_conn_rssi(conn, &rssi);
    }
    const int64_t probe_quiet_s = (esp_timer_get_time() - s_last_probe_rx_us) / 1000000;
    // Kept under 127 chars; keys before '=' (parsed by the app).
    snprintf(log->text, sizeof log->text, "rssi=%d mtu=%u fwd=%lu drop=%lu bad=%lu conns=%lu disc=%d probe=%llds heap=%lu",
             rssi, (unsigned)s_mtu, (unsigned long)s_fwd_frames, (unsigned long)s_drop_frames,
             (unsigned long)s_bad_frames, (unsigned long)s_conns, s_last_disc, (long long)probe_quiet_s,
             (unsigned long)(esp_get_free_heap_size() / 1024));
    pb_ostream_t os = pb_ostream_from_buffer(pb, sizeof pb);
    if (!pb_encode(&os, retrovision_v1_Envelope_fields, &env)) {
        return;
    }
    const size_t n = rv_frame_encode(pb, os.bytes_written, frame, sizeof frame);
    if (n > 0) {
        push_frame(frame, n);
    }
}

// ---------------------------------------------------------------------------- main

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

#if CONFIG_RV_RELAY_LED_GPIO >= 0
    gpio_reset_pin(CONFIG_RV_RELAY_LED_GPIO);
    gpio_set_direction(CONFIG_RV_RELAY_LED_GPIO, GPIO_MODE_OUTPUT);
    led_set(false);
#endif

    s_name_lock = xSemaphoreCreateMutex();
    s_up_lock = xSemaphoreCreateMutex();
    s_up = xStreamBufferCreate(UART_TO_BLE_BUF, 1);
    s_down = xStreamBufferCreate(BLE_TO_UART_BUF, 1);
    rv_frame_decoder_init(&s_peek);
    name_load();

    const uart_config_t ucfg = {
        .baud_rate = CONFIG_RV_RELAY_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    ESP_ERROR_CHECK(uart_driver_install(RELAY_UART, 8192, 2048, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(RELAY_UART, &ucfg));
    ESP_ERROR_CHECK(uart_set_pin(RELAY_UART, CONFIG_RV_RELAY_TX_GPIO, CONFIG_RV_RELAY_RX_GPIO, UART_PIN_NO_CHANGE,
                                 UART_PIN_NO_CHANGE));
    gpio_pullup_en(CONFIG_RV_RELAY_RX_GPIO);
    send_break(); // the relay (re)started: any session the probe thinks it has is gone

    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    // LE Secure Connections "just works", bonded: the same security as a probe's own BLE link.
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(s_svcs));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(s_svcs));
    ble_svc_gap_device_name_set("RV-relay");
    ble_store_config_init();

    xTaskCreatePinnedToCore(uart_rx_task, "relay_uart_rx", 4096, NULL, 10, NULL, 1);
    xTaskCreatePinnedToCore(uart_tx_task, "relay_uart_tx", 3072, NULL, 9, NULL, 1);
    xTaskCreatePinnedToCore(ble_tx_task, "relay_ble_tx", 4096, NULL, 9, NULL, 1);
    nimble_port_freertos_init(host_task);

    int64_t last_report = 0;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(250));
        const int64_t now = esp_timer_get_time();
        const bool connected = s_conn != BLE_HS_CONN_HANDLE_NONE;
        // LED: solid with a phone, slow blink while advertising, fast blink with no name yet.
        if (connected) {
            led_set(true);
        } else {
            const bool named = s_name[0] != 0;
            led_set(((now / (named ? 1000000 : 250000)) % (named ? 4 : 2)) == 0);
        }
        // Advertising can stop on its own, and a new name needs a restart.
        if (!connected && (s_name_changed || !ble_gap_adv_active())) {
            advertise();
        }
        // A connection that never subscribes or has gone silent (the app time-syncs every 30 s)
        // would lock the real phone out: one connection at a time.
        const uint16_t conn = s_conn;
        if (conn != BLE_HS_CONN_HANDLE_NONE) {
            const bool unsubscribed = !s_subscribed && now - s_conn_since_us > 20LL * 1000 * 1000;
            const bool silent = now - s_last_phone_rx_us > 90LL * 1000 * 1000;
            if (unsubscribed || silent) {
                ESP_LOGW(TAG, "dropping %s connection", unsubscribed ? "unsubscribed" : "silent");
                ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
            }
            if (s_subscribed && now - last_report >= 60LL * 1000 * 1000) {
                last_report = now;
                send_report();
            }
        } else {
            last_report = now - 50LL * 1000 * 1000; // first report ~10 s after a phone connects
        }
    }
}
