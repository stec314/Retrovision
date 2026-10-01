#include "link.h"

#include "sdkconfig.h"
#if CONFIG_IDF_TARGET_ESP32
// Classic ESP32: UART0 behind the board's USB-UART bridge (CP210x / CH340).
#include "driver/uart.h"
#define RV_UART UART_NUM_0
#define RV_UART_BAUD 921600
#else
#include "driver/usb_serial_jtag.h"
#endif
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "pb_decode.h"
#include "pb_encode.h"
#include "rv_framing.h"

static const char *TAG = "link";

static SemaphoreHandle_t s_tx_lock;
static uint32_t s_seq;
static rv_link_rx_cb_t s_on_envelope;
static volatile uint32_t s_rx_bad_pb;
#if CONFIG_IDF_TARGET_ESP32
static volatile int64_t s_last_rx_us;
#endif

// TX scratch, protected by s_tx_lock.
static uint8_t s_tx_pb[RV_MAX_ENVELOPE];
static uint8_t s_tx_frame[RV_FRAME_BUF_SIZE];

// RX state, owned by the rx task.
static rv_frame_decoder_t s_dec;
static retrovision_v1_Envelope s_rx_env;

static void rx_task(void *arg)
{
    uint8_t chunk[128];
    rv_frame_decoder_init(&s_dec);
    for (;;) {
#if CONFIG_IDF_TARGET_ESP32
        int n = uart_read_bytes(RV_UART, chunk, sizeof chunk, pdMS_TO_TICKS(20));
        if (n > 0) {
            s_last_rx_us = esp_timer_get_time();
        }
#else
        int n = usb_serial_jtag_read_bytes(chunk, sizeof chunk, pdMS_TO_TICKS(100));
#endif
        for (int i = 0; i < n; i++) {
            const uint8_t *env;
            size_t len;
            if (rv_frame_decoder_feed(&s_dec, chunk[i], &env, &len) != RV_FRAME_OK) {
                continue;
            }
            s_rx_env = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
            pb_istream_t is = pb_istream_from_buffer(env, len);
            if (!pb_decode(&is, retrovision_v1_Envelope_fields, &s_rx_env)) {
                s_rx_bad_pb++;
                ESP_LOGW(TAG, "bad envelope: %s", PB_GET_ERROR(&is));
                continue;
            }
            s_on_envelope(&s_rx_env);
        }
    }
}

void rv_link_init(rv_link_rx_cb_t on_envelope)
{
    s_on_envelope = on_envelope;
    s_tx_lock = xSemaphoreCreateMutex();
#if CONFIG_IDF_TARGET_ESP32
    const uart_config_t ucfg = {
        .baud_rate = RV_UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    ESP_ERROR_CHECK(uart_driver_install(RV_UART, 2048, 8192, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(RV_UART, &ucfg));
    ESP_ERROR_CHECK(uart_set_pin(RV_UART, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));
    s_last_rx_us = esp_timer_get_time();
#else
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 2048,
        .tx_buffer_size = 8192,
    };
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));
#endif
    xTaskCreatePinnedToCore(rx_task, "rv_link_rx", 4096, NULL, 10, NULL, tskNO_AFFINITY);
}

bool rv_link_send(retrovision_v1_Envelope *env, TickType_t timeout)
{
    if (xSemaphoreTake(s_tx_lock, timeout) != pdTRUE) {
        return false;
    }
    bool ok = false;
    env->seq = ++s_seq;
    if (s_seq == 0) {
        env->seq = s_seq = 1; // wrap: 0 is never used
    }
    pb_ostream_t os = pb_ostream_from_buffer(s_tx_pb, sizeof s_tx_pb);
    if (!pb_encode(&os, retrovision_v1_Envelope_fields, env)) {
        goto out; // cannot happen with bounded fields; logged by caller's counters
    }
    size_t n = rv_frame_encode(s_tx_pb, os.bytes_written, s_tx_frame, sizeof s_tx_frame);
    if (n == 0) {
        goto out;
    }
    // The driver queues into a byte ring buffer; a send either fits entirely
    // within `timeout` or returns 0, so frames are not cut in half here.
    // (If they ever were, the host decoder resyncs on the next delimiter.)
#if CONFIG_IDF_TARGET_ESP32
    // Blocks until the whole frame is in the 8 KB TX ring (drains at ~90 KB/s).
    ok = uart_write_bytes(RV_UART, s_tx_frame, n) == (int)n;
#else
    ok = usb_serial_jtag_write_bytes(s_tx_frame, n, timeout) == (int)n;
#endif
out:
    xSemaphoreGive(s_tx_lock);
    return ok;
}

bool rv_link_host_connected(void)
{
#if CONFIG_IDF_TARGET_ESP32
    // A UART cannot tell whether anyone listens. The host sends time-sync requests
    // every 30 s while a session is up, so silence for 2 minutes means it is gone.
    return esp_timer_get_time() - s_last_rx_us < 120LL * 1000 * 1000;
#else
    return usb_serial_jtag_is_connected();
#endif
}

uint32_t rv_link_rx_bad(void)
{
    return s_dec.bad_frames + s_rx_bad_pb;
}

void rv_link_flush(TickType_t timeout)
{
#if CONFIG_IDF_TARGET_ESP32
    uart_wait_tx_done(RV_UART, timeout);
#else
    usb_serial_jtag_wait_tx_done(timeout);
#endif
}
