#include "link_cfg.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_mac.h"
#include "mbedtls/md.h"
#include "nvs.h"

static const char *TAG = "link_cfg";
static rv_link_cfg_t s_cfg;

static void defaults(void)
{
    memset(&s_cfg, 0, sizeof s_cfg);
    s_cfg.mode = retrovision_v1_LinkKind_LINK_KIND_USB;
    s_cfg.port = RV_LINK_DEFAULT_PORT;
    uint8_t mac[6] = {0};
    esp_efuse_mac_get_default(mac);
    snprintf(s_cfg.name, sizeof s_cfg.name, "probe-%02x%02x", mac[4], mac[5]);
}

void rv_link_cfg_load(void)
{
    defaults();
    nvs_handle_t h;
    if (nvs_open("rv_link", NVS_READONLY, &h) != ESP_OK) {
        return;
    }
    rv_link_cfg_t tmp = s_cfg;
    size_t n = sizeof tmp;
    if (nvs_get_blob(h, "cfg", &tmp, &n) == ESP_OK && n == sizeof tmp) {
        tmp.name[sizeof tmp.name - 1] = 0;
        tmp.ssid[sizeof tmp.ssid - 1] = 0;
        tmp.password[sizeof tmp.password - 1] = 0;
        bool wireless = tmp.mode == retrovision_v1_LinkKind_LINK_KIND_BLE ||
                        tmp.mode == retrovision_v1_LinkKind_LINK_KIND_WIFI;
        if (!wireless || tmp.key_len >= 16) {
            s_cfg = tmp;
        }
    }
    nvs_close(h);
    ESP_LOGI(TAG, "link mode %d, name %s", s_cfg.mode, s_cfg.name);
}

const rv_link_cfg_t *rv_link_cfg(void)
{
    return &s_cfg;
}

bool rv_link_cfg_wireless(void)
{
    return s_cfg.mode == retrovision_v1_LinkKind_LINK_KIND_BLE ||
           s_cfg.mode == retrovision_v1_LinkKind_LINK_KIND_WIFI;
}

bool rv_link_cfg_save(const retrovision_v1_SetLink *in, char *msg, size_t cap)
{
    rv_link_cfg_t c;
    memset(&c, 0, sizeof c);
    c.mode = in->mode;
    switch (in->mode) {
    case retrovision_v1_LinkKind_LINK_KIND_USB:
    case retrovision_v1_LinkKind_LINK_KIND_UNSPECIFIED:
        c.mode = retrovision_v1_LinkKind_LINK_KIND_USB;
        break;
    case retrovision_v1_LinkKind_LINK_KIND_BLE:
        break;
    case retrovision_v1_LinkKind_LINK_KIND_WIFI:
        if (in->wifi_ssid[0] == 0) {
            snprintf(msg, cap, "wifi_ssid required");
            return false;
        }
        break;
    default:
        snprintf(msg, cap, "unknown mode %d", in->mode);
        return false;
    }
    if (c.mode != retrovision_v1_LinkKind_LINK_KIND_USB && in->key.size < 16) {
        snprintf(msg, cap, "key must be 16..32 bytes");
        return false;
    }
    memcpy(c.key, in->key.bytes, in->key.size);
    c.key_len = in->key.size;
    strlcpy(c.name, in->name[0] ? in->name : s_cfg.name, sizeof c.name);
    strlcpy(c.ssid, in->wifi_ssid, sizeof c.ssid);
    strlcpy(c.password, in->wifi_password, sizeof c.password);
    c.port = in->host_port ? (uint16_t)in->host_port : RV_LINK_DEFAULT_PORT;

    nvs_handle_t h;
    esp_err_t err = nvs_open("rv_link", NVS_READWRITE, &h);
    if (err == ESP_OK) {
        err = nvs_set_blob(h, "cfg", &c, sizeof c);
        if (err == ESP_OK) {
            err = nvs_commit(h);
        }
        nvs_close(h);
    }
    if (err != ESP_OK) {
        snprintf(msg, cap, "nvs: %s", esp_err_to_name(err));
        return false;
    }
    s_cfg = c;
    return true;
}

bool rv_link_auth_check(const uint8_t nonce[16], uint32_t boot_id, const uint8_t *mac, size_t mac_len)
{
    if (s_cfg.key_len < 16 || mac_len != 32) {
        return false;
    }
    uint8_t msg[7 + 16 + 4];
    memcpy(msg, "RVAUTH1", 7);
    memcpy(msg + 7, nonce, 16);
    for (int i = 0; i < 4; i++) {
        msg[23 + i] = (uint8_t)(boot_id >> (8 * i));
    }
    uint8_t want[32];
    const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (mbedtls_md_hmac(md, s_cfg.key, s_cfg.key_len, msg, sizeof msg, want) != 0) {
        return false;
    }
    uint8_t diff = 0;
    for (int i = 0; i < 32; i++) {
        diff |= want[i] ^ mac[i];
    }
    return diff == 0;
}
