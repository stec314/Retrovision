// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Multi-transport link core: framing + protobuf, shared TX, per-transport RX decoders.
#include "link.h"

#include "esp_log.h"
#include "freertos/semphr.h"
#include "link_cfg.h"
#include "pb_decode.h"
#include "pb_encode.h"
#include "rv_framing.h"

static const char *TAG = "link";

static SemaphoreHandle_t s_tx_lock;
static uint32_t s_seq;
static rv_link_rx_cb_t s_on_envelope;
static volatile uint32_t s_rx_bad_pb;
static volatile rv_transport_t s_active = RV_T_USB;
static const rv_transport_ops_t *s_ops[RV_T_COUNT];

// TX scratch, protected by s_tx_lock.
static uint8_t s_tx_pb[RV_MAX_ENVELOPE];
static uint8_t s_tx_frame[RV_FRAME_BUF_SIZE];

// RX state, one per transport (each fed from a single task).
static rv_frame_decoder_t s_dec[RV_T_COUNT];
static retrovision_v1_Envelope s_rx_env[RV_T_COUNT];

void rv_link_register(rv_transport_t t, const rv_transport_ops_t *ops)
{
    rv_frame_decoder_init(&s_dec[t]);
    s_ops[t] = ops;
}

void rv_link_reset_rx(rv_transport_t t)
{
    rv_frame_decoder_init(&s_dec[t]);
}

void rv_link_feed(rv_transport_t t, const uint8_t *data, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        const uint8_t *env;
        size_t n;
        if (rv_frame_decoder_feed(&s_dec[t], data[i], &env, &n) != RV_FRAME_OK) {
            continue;
        }
        s_rx_env[t] = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
        pb_istream_t is = pb_istream_from_buffer(env, n);
        if (!pb_decode(&is, retrovision_v1_Envelope_fields, &s_rx_env[t])) {
            s_rx_bad_pb++;
            ESP_LOGW(TAG, "bad envelope on %d: %s", t, PB_GET_ERROR(&is));
            continue;
        }
        s_on_envelope(&s_rx_env[t], t);
    }
}

void rv_link_init(rv_link_rx_cb_t on_envelope)
{
    s_on_envelope = on_envelope;
    s_tx_lock = xSemaphoreCreateMutex();
    rv_link_usb_init();
    // The BLE transport registers itself from the NimBLE init path (see capture/ble_scanner),
    // and only when the stored link mode is BLE.
}

bool rv_link_send_to(rv_transport_t t, retrovision_v1_Envelope *env, TickType_t timeout)
{
    const rv_transport_ops_t *ops = s_ops[t];
    if (!ops || !ops->connected()) {
        return false;
    }
    if (xSemaphoreTake(s_tx_lock, timeout) != pdTRUE) {
        return false;
    }
    bool ok = false;
    env->seq = ++s_seq;
    if (s_seq == 0) {
        env->seq = s_seq = 1; // wrap: 0 is never used
    }
    pb_ostream_t os = pb_ostream_from_buffer(s_tx_pb, sizeof s_tx_pb);
    if (pb_encode(&os, retrovision_v1_Envelope_fields, env)) {
        size_t n = rv_frame_encode(s_tx_pb, os.bytes_written, s_tx_frame, sizeof s_tx_frame);
        if (n > 0) {
            ok = ops->write(s_tx_frame, n, timeout);
        }
    }
    xSemaphoreGive(s_tx_lock);
    return ok;
}

bool rv_link_send(retrovision_v1_Envelope *env, TickType_t timeout)
{
    return rv_link_send_to(s_active, env, timeout);
}

void rv_link_set_active(rv_transport_t t)
{
    if (t != s_active) {
        ESP_LOGI(TAG, "active link: %d", t);
    }
    s_active = t;
}

rv_transport_t rv_link_active(void)
{
    return s_active;
}

bool rv_link_connected(rv_transport_t t)
{
    return s_ops[t] && s_ops[t]->connected();
}

bool rv_link_host_connected(void)
{
    return rv_link_connected(s_active);
}

retrovision_v1_LinkKind rv_link_kind(rv_transport_t t)
{
    switch (t) {
    case RV_T_BLE: return retrovision_v1_LinkKind_LINK_KIND_BLE;
    case RV_T_TCP: return retrovision_v1_LinkKind_LINK_KIND_WIFI;
    default: return retrovision_v1_LinkKind_LINK_KIND_USB;
    }
}

uint32_t rv_link_rx_bad(void)
{
    uint32_t n = s_rx_bad_pb;
    for (int t = 0; t < RV_T_COUNT; t++) {
        n += s_dec[t].bad_frames;
    }
    return n;
}

void rv_link_flush(TickType_t timeout)
{
    const rv_transport_ops_t *ops = s_ops[s_active];
    if (ops && ops->flush) {
        ops->flush(timeout);
    }
}
