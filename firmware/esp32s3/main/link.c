#include "link.h"

#include "driver/usb_serial_jtag.h"
#include "esp_check.h"
#include "esp_log.h"
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
        int n = usb_serial_jtag_read_bytes(chunk, sizeof chunk, pdMS_TO_TICKS(100));
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
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 2048,
        .tx_buffer_size = 8192,
    };
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));
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
    ok = usb_serial_jtag_write_bytes(s_tx_frame, n, timeout) == (int)n;
out:
    xSemaphoreGive(s_tx_lock);
    return ok;
}

bool rv_link_host_connected(void)
{
    return usb_serial_jtag_is_connected();
}

uint32_t rv_link_rx_bad(void)
{
    return s_dec.bad_frames + s_rx_bad_pb;
}

void rv_link_flush(TickType_t timeout)
{
    usb_serial_jtag_wait_tx_done(timeout);
}
