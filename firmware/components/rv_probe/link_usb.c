// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Cable transport: USB-Serial-JTAG (ESP32-S3/C5) or UART0 at 921600 baud (classic ESP32).
#include "link.h"
#include "link_cfg.h"

#include "sdkconfig.h"
#if CONFIG_IDF_TARGET_ESP32
#include "driver/uart.h"
#define RV_UART UART_NUM_0
#define RV_UART_BAUD 921600
#else
#include "driver/usb_serial_jtag.h"
#endif
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/task.h"

// Last time bytes arrived from the host. The host sends a time-sync request every 30 s
// while a session is up, so 2 minutes of silence means it is gone -- or it reopened the
// port and lost our session. Either way we go back to the handshake.
static volatile int64_t s_last_rx_us;

static void rx_task(void *arg)
{
    uint8_t chunk[128];
    for (;;) {
#if CONFIG_IDF_TARGET_ESP32
        int n = uart_read_bytes(RV_UART, chunk, sizeof chunk, pdMS_TO_TICKS(20));
#else
        int n = usb_serial_jtag_read_bytes(chunk, sizeof chunk, pdMS_TO_TICKS(100));
#endif
        if (n > 0) {
            s_last_rx_us = esp_timer_get_time();
            rv_link_feed(RV_T_USB, chunk, (size_t)n);
        }
    }
}

static bool usb_write(const uint8_t *data, size_t len, TickType_t timeout)
{
#if CONFIG_IDF_TARGET_ESP32
    (void)timeout;
    return uart_write_bytes(RV_UART, data, len) == (int)len;
#else
    return usb_serial_jtag_write_bytes(data, len, timeout) == (int)len;
#endif
}

static bool usb_connected(void)
{
    // With a BLE host attached, give up on a silent cable sooner (the host time-syncs every
    // 30 s), so unplugging hands over to BLE within ~40 s instead of 2 minutes.
    const int64_t window_us = (rv_link_connected(RV_T_BLE) ? 40LL : 120LL) * 1000 * 1000;
    const bool talking = esp_timer_get_time() - s_last_rx_us < window_us;
#if CONFIG_IDF_TARGET_ESP32
    // A UART cannot tell whether anyone listens: silence is the only signal.
    return talking;
#else
    return talking && usb_serial_jtag_is_connected();
#endif
}

static void usb_flush(TickType_t timeout)
{
#if CONFIG_IDF_TARGET_ESP32
    uart_wait_tx_done(RV_UART, timeout);
#else
    usb_serial_jtag_wait_tx_done(timeout);
#endif
}

static const rv_transport_ops_t s_ops = {usb_write, usb_connected, usb_flush};

void rv_link_usb_init(void)
{
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
#else
    usb_serial_jtag_driver_config_t cfg = {
        .rx_buffer_size = 2048,
        .tx_buffer_size = 8192,
    };
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));
#endif
    // Assume a host at boot (the app opens the port, which resets the board) -- unless the probe
    // is set up for a wireless link, where it most likely runs on a power bank: then the cable
    // counts only once a host actually talks on it.
    s_last_rx_us = rv_link_cfg()->mode == retrovision_v1_LinkKind_LINK_KIND_USB
                       ? esp_timer_get_time()
                       : esp_timer_get_time() - 600LL * 1000 * 1000;
    rv_link_register(RV_T_USB, &s_ops);
    xTaskCreatePinnedToCore(rx_task, "rv_link_usb", 4096, NULL, 10, NULL, tskNO_AFFINITY);
}
