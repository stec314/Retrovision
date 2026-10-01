// Wireless link settings, stored in NVS by SetLink (USB only) and read at boot.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "retrovision.pb.h"

#define RV_LINK_KEY_MAX 32
#define RV_LINK_DEFAULT_PORT 7878
#define RV_LINK_DISCOVERY_PORT 7879

typedef struct {
    retrovision_v1_LinkKind mode;   // USB = wireless off
    char name[25];
    uint8_t key[RV_LINK_KEY_MAX];
    uint8_t key_len;
    char ssid[33];
    char password[65];
    uint16_t port;
} rv_link_cfg_t;

// Loads the settings (defaults: USB only, name from the MAC). Call once after nvs_flash_init.
void rv_link_cfg_load(void);
const rv_link_cfg_t *rv_link_cfg(void);
bool rv_link_cfg_wireless(void);

// Validates and stores a SetLink. Returns false with a reason in msg on invalid input.
bool rv_link_cfg_save(const retrovision_v1_SetLink *in, char *msg, size_t cap);

// HMAC-SHA256(key, "RVAUTH1" || nonce || boot_id LE) and a constant-time compare against mac.
bool rv_link_auth_check(const uint8_t nonce[16], uint32_t boot_id, const uint8_t *mac, size_t mac_len);
