// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Host links. One protocol (COBS frames carrying protobuf Envelopes) over up to
// three transports: the cable (always on), plus a BLE GATT link when configured.
// The session talks to one "active" transport: the one it handshook on.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "freertos/FreeRTOS.h"
#include "retrovision.pb.h"

typedef enum {
    RV_T_USB = 0,   // USB-Serial-JTAG (ESP32-S3/C5) or UART0 via USB bridge (classic ESP32)
    RV_T_BLE = 1,   // GATT, Nordic UART Service layout
    RV_T_TCP = 2,   // reserved for a future Wi-Fi/TCP link
    RV_T_COUNT
} rv_transport_t;

typedef void (*rv_link_rx_cb_t)(const retrovision_v1_Envelope *env, rv_transport_t from);

typedef struct {
    // Writes a whole frame or nothing (from the caller's point of view).
    bool (*write)(const uint8_t *data, size_t len, TickType_t timeout);
    bool (*connected)(void);
    void (*flush)(TickType_t timeout);
} rv_transport_ops_t;

// Starts the cable transport, and the BLE transport if the stored link mode is BLE.
void rv_link_init(rv_link_rx_cb_t on_envelope);

// Encode, frame and write one envelope. Assigns Envelope.seq. Thread-safe.
bool rv_link_send(retrovision_v1_Envelope *env, TickType_t timeout);
bool rv_link_send_to(rv_transport_t t, retrovision_v1_Envelope *env, TickType_t timeout);

void rv_link_set_active(rv_transport_t t);
rv_transport_t rv_link_active(void);
bool rv_link_connected(rv_transport_t t);
// True while the active transport has a host attached.
bool rv_link_host_connected(void);
retrovision_v1_LinkKind rv_link_kind(rv_transport_t t);

uint32_t rv_link_rx_bad(void);
void rv_link_flush(TickType_t timeout);

// For transport implementations.
void rv_link_register(rv_transport_t t, const rv_transport_ops_t *ops);
void rv_link_feed(rv_transport_t t, const uint8_t *data, size_t len);
void rv_link_reset_rx(rv_transport_t t);

// Transport entry points.
void rv_link_usb_init(void);
void rv_link_ble_register_gatt(void);   // before the NimBLE host starts (BLE link mode only)
void rv_link_ble_on_sync(void);         // NimBLE host synced: start advertising
// RSSI of the connected BLE host (dBm), 0 if none.
int rv_link_ble_rssi(void);
