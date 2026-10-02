// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Host-only helper: decode an Envelope with nanopb and re-encode it.
// Proves the generated code + size bounds accept every conformance vector.
#include <pb_decode.h>
#include <pb_encode.h>

#include "retrovision.pb.h"

long rv_test_pb_roundtrip(const uint8_t *in, size_t len, uint8_t *out, size_t cap)
{
    retrovision_v1_Envelope env = retrovision_v1_Envelope_init_zero;
    pb_istream_t is = pb_istream_from_buffer(in, len);
    if (!pb_decode(&is, retrovision_v1_Envelope_fields, &env)) {
        return -1;
    }
    pb_ostream_t os = pb_ostream_from_buffer(out, cap);
    if (!pb_encode(&os, retrovision_v1_Envelope_fields, &env)) {
        return -2;
    }
    return (long)os.bytes_written;
}

// Host-only helper: run the firmware's Config validation on an encoded
// retrovision.v1.Config and print a summary the Python test can assert on.
#include <stdio.h>

#include "../../rv_probe/config.h"

int rv_test_cfg(const uint8_t *in, size_t len, char *out, size_t cap)
{
    retrovision_v1_Config pb = retrovision_v1_Config_init_zero;
    pb_istream_t is = pb_istream_from_buffer(in, len);
    if (!pb_decode(&is, retrovision_v1_Config_fields, &pb)) {
        return -1;
    }
    rv_cfg_t c;
    char msg[96];
    int r = (int)rv_cfg_from_pb(&pb, &c, msg, sizeof msg);
    snprintf(out, cap,
             "wifi=%d hops=%u first=%u:%u mask=0x%lx ies=%d pdd=%lu bdd=%lu wrssi=%d "
             "ble=%d active=%d itvl=%u win=%u ext=%d bled=%lu mode=%d status=%lu msg=%s",
             c.wifi_enabled, c.hop_count, c.hop[0].channel, c.hop[0].dwell_ms,
             (unsigned long)c.wifi_type_mask, c.forward_raw_ies,
             (unsigned long)c.probe_req_dedup_ms, (unsigned long)c.beacon_dedup_ms,
             c.wifi_min_rssi, c.ble_enabled, c.ble_active, c.ble_itvl, c.ble_window,
             c.ble_extended, (unsigned long)c.ble_dedup_ms, (int)c.radio_mode,
             (unsigned long)c.status_interval_s, msg);
    return r;
}
