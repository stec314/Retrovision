// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Relay transport: a second UART wired to a relay board (ESP32-S3 running firmware/relay-esp32s3)
// that carries the bytes to the phone over BLE. Started only in relay link mode.
//
// Why: in BLE link mode the probe's own Bluetooth radio carries the link and cannot scan, so a
// probe on a power bank goes deaf to trackers. With the relay the radio keeps scanning and the
// relay's radio does the talking.
//
// Three wires: probe TX -> relay RX, probe RX <- relay TX, GND. The relay knows when the phone
// connects or leaves and says so out of band with a UART break: on a break the probe treats the
// host as gone and goes back to the handshake at once, instead of streaming into the void until
// the silence timeout. The relay is not trusted with anything: it cannot forge the HMAC answer
// (the pairing key never leaves the probe and the phone).
#include "link.h"
#include "link_cfg.h"

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "sdkconfig.h"

#if CONFIG_IDF_TARGET_ESP32
#define RV_RELAY_UART UART_NUM_2 // UART0 is the cable link on the classic ESP32
#else
#define RV_RELAY_UART UART_NUM_1
#endif

static const char *TAG = "link_uart";

static QueueHandle_t s_events;
static volatile int64_t s_last_rx_us;
static volatile uint32_t s_breaks;

static void rx_task(void *arg)
{
    uint8_t chunk[256];
    uart_event_t ev;
    for (;;) {
        if (xQueueReceive(s_events, &ev, portMAX_DELAY) != pdTRUE) {
            continue;
        }
        switch (ev.type) {
        case UART_DATA: {
            size_t left = ev.size;
            while (left > 0) {
                int n = uart_read_bytes(RV_RELAY_UART, chunk, left < sizeof chunk ? left : sizeof chunk, 0);
                if (n <= 0) {
                    break;
                }
                s_last_rx_us = esp_timer_get_time();
                rv_link_feed(RV_T_UART, chunk, (size_t)n);
                left -= (size_t)n;
            }
            break;
        }
        case UART_BREAK:
            // The relay's phone connected or left: whatever session ran is over.
            s_breaks++;
            s_last_rx_us = 0;
            rv_link_reset_rx(RV_T_UART);
            break;
        case UART_FIFO_OVF:
        case UART_BUFFER_FULL:
            uart_flush_input(RV_RELAY_UART);
            xQueueReset(s_events);
            rv_link_reset_rx(RV_T_UART);
            break;
        default:
            break;
        }
    }
}

static bool uart_write(const uint8_t *data, size_t len, TickType_t timeout)
{
    (void)timeout;
    return uart_write_bytes(RV_RELAY_UART, data, len) == (int)len;
}

static bool uart_connected(void)
{
    // The host time-syncs every 30 s; the relay drops a silent phone after 90 s and breaks.
    const int64_t last = s_last_rx_us;
    return last != 0 && esp_timer_get_time() - last < 100LL * 1000 * 1000;
}

static void uart_flush(TickType_t timeout)
{
    uart_wait_tx_done(RV_RELAY_UART, timeout);
}

static const rv_transport_ops_t s_ops = {uart_write, uart_connected, uart_flush};

uint32_t rv_link_uart_breaks(void)
{
    return s_breaks;
}

void rv_link_uart_init(void)
{
    const uart_config_t ucfg = {
        .baud_rate = CONFIG_RV_RELAY_UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    // TX buffer large enough for a burst of observations: uart_write_bytes then only copies.
    ESP_ERROR_CHECK(uart_driver_install(RV_RELAY_UART, 2048, 16384, 16, &s_events, 0));
    ESP_ERROR_CHECK(uart_param_config(RV_RELAY_UART, &ucfg));
    ESP_ERROR_CHECK(uart_set_pin(RV_RELAY_UART, CONFIG_RV_RELAY_UART_TX_GPIO, CONFIG_RV_RELAY_UART_RX_GPIO,
                                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));
    // Idle line is high; a pulled-up RX keeps a disconnected relay from reading as a break storm.
    gpio_pullup_en(CONFIG_RV_RELAY_UART_RX_GPIO);
    rv_link_register(RV_T_UART, &s_ops);
    xTaskCreatePinnedToCore(rx_task, "rv_link_uart", 4096, NULL, 10, NULL, tskNO_AFFINITY);
    ESP_LOGI(TAG, "relay UART%d tx=%d rx=%d %d baud", RV_RELAY_UART, CONFIG_RV_RELAY_UART_TX_GPIO,
             CONFIG_RV_RELAY_UART_RX_GPIO, CONFIG_RV_RELAY_UART_BAUD);
}
