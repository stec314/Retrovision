#include "ble_scanner.h"

#include <string.h>

#include "capture.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"

static const char *TAG = "ble";

static volatile bool s_synced;
static volatile bool s_want;
static const rv_cfg_t *s_cfg;

// GAP events arrive on the NimBLE host task only: static scratch is safe.
static rv_raw_item_t s_item;

// Single-slot reassembly for chained extended advertising reports.
static struct {
    bool active;
    uint8_t addr[6];
    uint8_t sid;
    uint16_t len;
    uint8_t data[RV_RAW_BLE_MAX];
} s_frag;

static int gap_cb(struct ble_gap_event *ev, void *arg);

// Legacy PDU type (BLE_HCI_ADV_RPT_EVTYPE_*) -> retrovision.v1.BleAdvType.
static uint8_t map_legacy(uint8_t evtype)
{
    return evtype <= BLE_HCI_ADV_RPT_EVTYPE_SCAN_RSP ? (uint8_t)(evtype + 1) : 0;
}

static void submit(const ble_addr_t *addr, int8_t rssi, uint8_t adv_type, uint8_t prim_phy,
                   uint8_t sec_phy, int8_t tx_power, const uint8_t *data, size_t len)
{
    g_rv_ble_seen++;
    if (rssi == 127) {
        rssi = 0; // unavailable
    } else if (rssi < s_cfg->ble_min_rssi) {
        return;
    }
    s_item.kind = RV_RAW_BLE;
    s_item.ts_us = esp_timer_get_time();
    s_item.rssi = rssi;
    s_item.channel = 0;
    s_item.cut = len > RV_RAW_BLE_MAX;
    s_item.len = (uint16_t)(s_item.cut ? RV_RAW_BLE_MAX : len);
    memcpy(s_item.ble.addr, addr->val, 6);
    s_item.ble.addr_random = addr->type == BLE_ADDR_RANDOM || addr->type == BLE_ADDR_RANDOM_ID;
    s_item.ble.adv_type = adv_type;
    s_item.ble.prim_phy = prim_phy;
    s_item.ble.sec_phy = sec_phy;
    s_item.ble.tx_power = tx_power;
    memcpy(s_item.ble.data, data, s_item.len);
    rv_capture_submit(&s_item);
}

#if MYNEWT_VAL(BLE_EXT_ADV)
static void on_ext(const struct ble_gap_ext_disc_desc *d)
{
    const uint8_t type = (d->props & BLE_HCI_ADV_LEGACY_MASK) ? map_legacy(d->legacy_event_type)
                                                               : 6 /* EXTENDED */;
    const bool same = s_frag.active && s_frag.sid == d->sid && memcmp(s_frag.addr, d->addr.val, 6) == 0;

    if (d->data_status == BLE_GAP_EXT_ADV_DATA_STATUS_INCOMPLETE) {
        if (!same) {
            s_frag.active = true;
            s_frag.sid = d->sid;
            memcpy(s_frag.addr, d->addr.val, 6);
            s_frag.len = 0;
        }
        size_t room = RV_RAW_BLE_MAX - s_frag.len;
        size_t n = d->length_data < room ? d->length_data : room;
        memcpy(s_frag.data + s_frag.len, d->data, n);
        s_frag.len += (uint16_t)n;
        return;
    }

    const uint8_t *data = d->data;
    size_t len = d->length_data;
    if (same) {
        // Last fragment (COMPLETE or TRUNCATED): append and emit the whole.
        size_t room = RV_RAW_BLE_MAX - s_frag.len;
        size_t n = len < room ? len : room;
        memcpy(s_frag.data + s_frag.len, data, n);
        s_frag.len += (uint16_t)n;
        data = s_frag.data;
        len = s_frag.len;
        s_frag.active = false;
    }
    submit(&d->addr, d->rssi, type, d->prim_phy, d->sec_phy, d->tx_power, data, len);
}
#endif

static void start_scan(void)
{
    uint8_t own_addr_type;
    if (ble_hs_id_infer_auto(0, &own_addr_type) != 0) {
        own_addr_type = BLE_OWN_ADDR_RANDOM;
    }
    int rc;
#if MYNEWT_VAL(BLE_EXT_ADV)
    if (s_cfg->ble_extended) {
        const struct ble_gap_ext_disc_params p = {
            .itvl = s_cfg->ble_itvl,
            .window = s_cfg->ble_window,
            .passive = !s_cfg->ble_active,
        };
        // duration 0 = forever; no controller duplicate filtering, we dedup
        // ourselves; 1M PHY only (Coded PHY would halve Wi-Fi air time).
        rc = ble_gap_ext_disc(own_addr_type, 0, 0, 0, BLE_HCI_SCAN_FILT_NO_WL, 0, &p, NULL,
                              gap_cb, NULL);
    } else
#endif
    {
        const struct ble_gap_disc_params p = {
            .itvl = s_cfg->ble_itvl,
            .window = s_cfg->ble_window,
            .filter_policy = BLE_HCI_SCAN_FILT_NO_WL,
            .passive = !s_cfg->ble_active,
            .filter_duplicates = 0,
        };
        rc = ble_gap_disc(own_addr_type, BLE_HS_FOREVER, &p, gap_cb, NULL);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "scan start failed: %d", rc);
    } else {
        ESP_LOGI(TAG, "scanning (%s, %s)", s_cfg->ble_extended ? "extended" : "legacy",
                 s_cfg->ble_active ? "active" : "passive");
    }
}

static int gap_cb(struct ble_gap_event *ev, void *arg)
{
    switch (ev->type) {
    case BLE_GAP_EVENT_DISC:
        if (s_want) {
            const struct ble_gap_disc_desc *d = &ev->disc;
            submit(&d->addr, d->rssi, map_legacy(d->event_type), 1, 0, 127, d->data,
                   d->length_data);
        }
        return 0;
#if MYNEWT_VAL(BLE_EXT_ADV)
    case BLE_GAP_EVENT_EXT_DISC:
        if (s_want) {
            on_ext(&ev->ext_disc);
        }
        return 0;
#endif
    case BLE_GAP_EVENT_DISC_COMPLETE:
        // Scans run forever; completion means the controller stopped them
        // (e.g. coexistence or reset). Restart if we still want to scan.
        if (s_want && s_synced) {
            start_scan();
        }
        return 0;
    default:
        return 0;
    }
}

static void on_sync(void)
{
    s_synced = true;
    if (s_want) {
        start_scan();
    }
}

static void on_reset(int reason)
{
    s_synced = false;
    ESP_LOGW(TAG, "host reset, reason %d", reason);
}

static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void rv_ble_scanner_init(void)
{
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    nimble_port_freertos_init(host_task);
}

void rv_ble_scanner_start(const rv_cfg_t *cfg)
{
    s_cfg = cfg;
    s_frag.active = false;
    s_want = true;
    if (s_synced) {
        start_scan();
    }
}

void rv_ble_scanner_stop(void)
{
    s_want = false;
    if (ble_gap_disc_active()) {
        ble_gap_disc_cancel();
    }
}
