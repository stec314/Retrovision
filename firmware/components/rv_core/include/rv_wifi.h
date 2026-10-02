// 802.11 frame parsing for the Wi-Fi sniffer. Portable C99.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RV_MAX_SSID 32
#define RV_MAX_RAW_IES 320  // keep in sync with retrovision.options WifiFrame.raw_ies

// Values equal retrovision.v1.WifiFrameType so they can be assigned directly.
typedef enum {
    RV_WIFI_UNSPECIFIED = 0,
    RV_WIFI_PROBE_REQ = 1,
    RV_WIFI_PROBE_RESP = 2,
    RV_WIFI_BEACON = 3,
    RV_WIFI_ASSOC_REQ = 4,
    RV_WIFI_REASSOC_REQ = 5,
    RV_WIFI_AUTH = 6,
    RV_WIFI_DEAUTH = 7,
    RV_WIFI_DISASSOC = 8,
    RV_WIFI_DATA = 9,
    RV_WIFI_ACTION = 10,
} rv_wifi_type_t;

typedef struct {
    rv_wifi_type_t type;
    uint8_t addr1[6];
    uint8_t addr2[6];
    uint8_t addr3[6];
    uint16_t seq;              // 12-bit sequence number
    uint8_t ssid[RV_MAX_SSID];
    uint8_t ssid_len;
    bool has_ssid;             // SSID element present (may be zero length = wildcard)
    uint64_t tsf;              // beacon / probe-response timestamp (AP uptime, µs), 0 if absent
    const uint8_t *ies;        // points into the input buffer
    uint16_t ies_len;          // possibly cut at an element boundary to RV_MAX_RAW_IES
    bool ies_truncated;
} rv_wifi_frame_t;

// Frame type from the first frame-control byte, without parsing the rest.
// Lets the capture callback filter cheaply. Returns RV_WIFI_UNSPECIFIED for
// frames we never forward (control frames, unknown subtypes).
rv_wifi_type_t rv_wifi_classify(uint8_t fc0);

// Parse one 802.11 frame. `len` must NOT include the FCS.
// Returns false for frames that are too short or not supported.
// A malformed IE list is not fatal: IEs up to the last well-formed element
// are kept and ies_truncated is set.
bool rv_wifi_parse(const uint8_t *frame, size_t len, rv_wifi_frame_t *out);

#ifdef __cplusplus
}
#endif
