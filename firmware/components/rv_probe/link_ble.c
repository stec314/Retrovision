// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// BLE transport: GATT server with the Nordic UART Service layout.
//   service 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
//   RX 6E400002 (host -> probe, write / write without response, encrypted link required)
//   TX 6E400003 (probe -> host, notify)
// Frames are a byte stream: COBS delimiters make notification boundaries irrelevant.
// Security: LE Secure Connections "just works" bonding encrypts the link against passive
// sniffing; the HMAC challenge in Hello/HelloAck (pairing key) decides who gets data.
#include "link.h"
#include "link_cfg.h"

#include <string.h>

#include "esp_log.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_hs_adv.h"
#include "nimble/nimble_port.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "esp_heap_caps.h"
#include "esp_system.h"
#include "store/config/ble_store_config.h"
#include "host/ble_store.h"
#include "esp_timer.h"
#include "freertos/stream_buffer.h"
#include "freertos/task.h"

static const char *TAG = "link_ble";

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

// The NimBLE host task must never block: incoming bytes are handed to a small task of our own
// (decoding takes the session lock, which another task may hold while it sends to us), and sends
// from inside the host task do not wait for buffers that only the host task can free.
static StreamBufferHandle_t s_rx;
static volatile TaskHandle_t s_host_task;
static volatile uint32_t s_rx_dropped;
static volatile int64_t s_conn_since_us;
static volatile int64_t s_last_rx_us;

// Diagnostics, reported to the host over the cable (rv_link_ble_report).
static volatile bool s_synced;
static volatile int s_adv_rc;              // last advertise() failure code, 0 = ok
static const char *volatile s_adv_step = "nosync";
static volatile uint32_t s_conns;
static volatile int s_last_disc = -1;
static volatile uint32_t s_heap_at_sync;

static int gap_event(struct ble_gap_event *ev, void *arg);
static void advertise(void);

static int rx_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    uint8_t buf[256];
    uint16_t total = OS_MBUF_PKTLEN(ctxt->om);
    uint16_t off = 0;
    s_last_rx_us = esp_timer_get_time();
    while (off < total) {
        uint16_t n = total - off > sizeof buf ? sizeof buf : total - off;
        if (os_mbuf_copydata(ctxt->om, off, n, buf) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        // Never wait here: a full buffer means the decoder is stuck, and the host must keep going.
        if (xStreamBufferSend(s_rx, buf, n, 0) != n) {
            s_rx_dropped++;
        }
        off += n;
    }
    return 0;
}

static void rx_task(void *arg)
{
    uint8_t buf[128];
    for (;;) {
        size_t n = xStreamBufferReceive(s_rx, buf, sizeof buf, portMAX_DELAY);
        if (n > 0) {
            rv_link_feed(RV_T_BLE, buf, n);
        }
    }
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

static bool ble_write(const uint8_t *data, size_t len, TickType_t timeout)
{
    TickType_t start = xTaskGetTickCount();
    size_t off = 0;
    while (off < len) {
        uint16_t conn = s_conn;
        if (conn == BLE_HS_CONN_HANDLE_NONE || !s_subscribed) {
            return false;
        }
        size_t chunk = s_mtu > 3 ? s_mtu - 3 : 20;
        if (chunk > len - off) {
            chunk = len - off;
        }
        struct os_mbuf *om = ble_hs_mbuf_from_flat(data + off, chunk);
        int rc = om ? ble_gatts_notify_custom(conn, s_tx_handle, om) : BLE_HS_ENOMEM;
        if (rc == 0) {
            off += chunk;
            continue;
        }
        if (rc != BLE_HS_ENOMEM && rc != BLE_HS_EBUSY) {
            return false; // no log: a log line from here would be forwarded back into this path
        }
        if (xTaskGetCurrentTaskHandle() == s_host_task) {
            return false; // only the host task frees these buffers: waiting here would stall it
        }
        // Controller buffers full: wait for them to drain.
        if (xTaskGetTickCount() - start > timeout + pdMS_TO_TICKS(200)) {
            return false;
        }
        vTaskDelay(pdMS_TO_TICKS(5));
    }
    return true;
}

static bool ble_connected(void)
{
    return s_conn != BLE_HS_CONN_HANDLE_NONE && s_subscribed;
}

static void ble_flush(TickType_t timeout)
{
    vTaskDelay(pdMS_TO_TICKS(100));
}

static const rv_transport_ops_t s_ops = {ble_write, ble_connected, ble_flush};

int rv_link_ble_rssi(void)
{
    uint16_t conn = s_conn;
    int8_t rssi = 0;
    if (conn == BLE_HS_CONN_HANDLE_NONE || ble_gap_conn_rssi(conn, &rssi) != 0) {
        return 0;
    }
    return rssi;
}

void rv_link_ble_register_gatt(void)
{
    // LE Secure Connections, no I/O ("just works"), bonded so the phone reconnects silently.
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    // Bond store full (re-pairings pile up): drop the oldest bond instead of failing the pairing.
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(s_svcs));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(s_svcs));
    char name[32];
    snprintf(name, sizeof name, "RV-%s", rv_link_cfg()->name);
    ble_svc_gap_device_name_set(name);
    ble_store_config_init();
    s_rx = xStreamBufferCreate(2048, 1);
    xTaskCreatePinnedToCore(rx_task, "rv_link_ble_rx", 4096, NULL, 9, NULL, tskNO_AFFINITY);
    rv_link_register(RV_T_BLE, &s_ops);
}

static void advertise(void)
{
    struct ble_hs_adv_fields f;
    memset(&f, 0, sizeof f);
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.uuids128 = &NUS_SVC;
    f.num_uuids128 = 1;
    f.uuids128_is_complete = 1;

    struct ble_hs_adv_fields rsp;
    memset(&rsp, 0, sizeof rsp);
    const char *name = ble_svc_gap_device_name();
    rsp.name = (const uint8_t *)name;
    rsp.name_len = strlen(name) > 29 ? 29 : strlen(name);
    rsp.name_is_complete = strlen(name) <= 29;

#if MYNEWT_VAL(BLE_EXT_ADV)
    // Extended-advertising builds must use the ext API; a legacy PDU keeps old phones happy.
    struct ble_gap_ext_adv_params p;
    memset(&p, 0, sizeof p);
    p.connectable = 1;
    p.scannable = 1;
    p.legacy_pdu = 1;
    p.own_addr_type = s_own_addr_type;
    p.primary_phy = BLE_HCI_LE_PHY_1M;
    p.secondary_phy = BLE_HCI_LE_PHY_1M;
    p.itvl_min = BLE_GAP_ADV_ITVL_MS(200);
    p.itvl_max = BLE_GAP_ADV_ITVL_MS(300);
    p.sid = 0;
    if (ble_gap_ext_adv_active(0)) {
        return;
    }
    int rc = ble_gap_ext_adv_configure(0, &p, NULL, gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv configure: %d", rc);
        s_adv_step = "fail-configure"; s_adv_rc = rc;
        return;
    }
    struct os_mbuf *data = os_msys_get_pkthdr(BLE_HS_ADV_MAX_SZ, 0);
    struct os_mbuf *scan = os_msys_get_pkthdr(BLE_HS_ADV_MAX_SZ, 0);
    if (!data || !scan || ble_hs_adv_set_fields_mbuf(&f, data) != 0 || ble_hs_adv_set_fields_mbuf(&rsp, scan) != 0) {
        ESP_LOGE(TAG, "adv data");
        if (data) os_mbuf_free_chain(data);
        if (scan) os_mbuf_free_chain(scan);
        s_adv_step = "fail-advdata"; s_adv_rc = -1;
        return;
    }
    rc = ble_gap_ext_adv_set_data(0, data);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv set data: %d", rc);
        os_mbuf_free_chain(scan);
        s_adv_step = "fail-setdata"; s_adv_rc = rc;
        return;
    }
    rc = ble_gap_ext_adv_rsp_set_data(0, scan);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv set rsp: %d", rc);
        s_adv_step = "fail-setrsp"; s_adv_rc = rc;
        return;
    }
    rc = ble_gap_ext_adv_start(0, 0, 0);
#else
    if (ble_gap_adv_active()) {
        return;
    }
    ble_gap_adv_set_fields(&f);
    ble_gap_adv_rsp_set_fields(&rsp);
    struct ble_gap_adv_params p;
    memset(&p, 0, sizeof p);
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    p.itvl_min = BLE_GAP_ADV_ITVL_MS(200);
    p.itvl_max = BLE_GAP_ADV_ITVL_MS(300);
    int rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
#endif
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG, "adv start: %d", rc);
        s_adv_step = "fail-start"; s_adv_rc = rc;
    } else {
        ESP_LOGI(TAG, "advertising as %s", name);
        s_adv_step = "adv"; s_adv_rc = 0;
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
            rv_link_reset_rx(RV_T_BLE);
            s_conn_since_us = s_last_rx_us = esp_timer_get_time();
            s_conns++;
            ESP_LOGI(TAG, "connected");
        } else {
            advertise();
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (%d)", ev->disconnect.reason);
        s_last_disc = ev->disconnect.reason;
        s_conn = BLE_HS_CONN_HANDLE_NONE;
        s_subscribed = false;
        rv_link_reset_rx(RV_T_BLE);
        advertise();
        return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        if (s_conn == BLE_HS_CONN_HANDLE_NONE) {
            advertise();
        }
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (ev->subscribe.attr_handle == s_tx_handle) {
            s_subscribed = ev->subscribe.cur_notify;
        }
        return 0;
    case BLE_GAP_EVENT_MTU:
        s_mtu = ev->mtu.value;
        return 0;
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        // The phone forgot the bond (e.g. "forget device"): pair again rather than fail.
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    default:
        return 0;
    }
}

static bool adv_active(void)
{
#if MYNEWT_VAL(BLE_EXT_ADV)
    return ble_gap_ext_adv_active(0);
#else
    return ble_gap_adv_active();
#endif
}

void rv_link_ble_tick(void)
{
    // Advertising can stop on its own (a failed start, a controller reset, coexistence
    // hiccups): restart it whenever nobody is connected.
    if (s_synced && s_conn == BLE_HS_CONN_HANDLE_NONE && !adv_active()) {
        advertise();
    }
    // A connection that never subscribes, or that has gone silent (the app time-syncs every
    // 30 s), is dead weight: with one connection allowed it would lock the real phone out.
    const uint16_t conn = s_conn;
    if (conn != BLE_HS_CONN_HANDLE_NONE) {
        const int64_t now = esp_timer_get_time();
        const bool unsubscribed = !s_subscribed && now - s_conn_since_us > 20LL * 1000 * 1000;
        const bool silent = now - s_last_rx_us > 90LL * 1000 * 1000;
        if (unsubscribed || silent) {
            ESP_LOGW(TAG, "dropping %s connection", unsubscribed ? "unsubscribed" : "silent");
            ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        }
    }
}

void rv_link_ble_report(char *buf, size_t cap)
{
    // Kept under 127 chars: it travels as a Log frame. Parsed by the app (keys before '=').
    static const char *const reasons[] = {"unknown", "poweron", "ext", "sw", "panic", "intwdt", "taskwdt", "wdt",
                                           "deepsleep", "brownout", "sdio", "usb", "jtag", "efuse", "pwrglitch", "cpulock"};
    const int rr = (int)esp_reset_reason();
    snprintf(buf, cap, "st=%s rc=%d name=%s boot=%s conns=%lu disc=%d rxdrop=%lu heap=%lu/%lu/%lu",
             !s_synced ? "nosync" : s_conn != BLE_HS_CONN_HANDLE_NONE ? "connected" : (adv_active() ? "adv" : s_adv_step),
             s_adv_rc, ble_svc_gap_device_name(), rr >= 0 && rr < 16 ? reasons[rr] : "other", (unsigned long)s_conns, s_last_disc, (unsigned long)s_rx_dropped,
             (unsigned long)(s_heap_at_sync / 1024), (unsigned long)(esp_get_free_heap_size() / 1024),
             (unsigned long)(esp_get_minimum_free_heap_size() / 1024));
}

void rv_link_ble_on_sync(void)
{
    s_host_task = xTaskGetCurrentTaskHandle(); // on_sync runs in the NimBLE host task
    s_synced = true;
    s_heap_at_sync = esp_get_free_heap_size();
    if (ble_hs_id_infer_auto(0, &s_own_addr_type) != 0) {
        s_own_addr_type = BLE_OWN_ADDR_PUBLIC;
    }
    advertise();
}
