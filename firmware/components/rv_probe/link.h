// Host link: framing + protobuf over the native USB-Serial-JTAG port (ESP32-S3)
// or UART0 at 921600 baud through the USB-UART bridge (classic ESP32).
#pragma once

#include <stdbool.h>

#include "freertos/FreeRTOS.h"
#include "retrovision.pb.h"

typedef void (*rv_link_rx_cb_t)(const retrovision_v1_Envelope *env);

void rv_link_init(rv_link_rx_cb_t on_envelope);

// Encode, frame and write one envelope. Assigns Envelope.seq.
// Thread-safe. Returns false if the link lock or USB TX could not be
// obtained within `timeout` (the frame is then dropped, never half-written
// from the caller's point of view: a partial USB write is resynchronised by
// the host decoder on the next delimiter).
bool rv_link_send(retrovision_v1_Envelope *env, TickType_t timeout);

// True while a USB host is attached (SOF seen recently).
bool rv_link_host_connected(void);

// Frames from the host that failed COBS/CRC/protobuf decoding.
uint32_t rv_link_rx_bad(void);

// Block until the TX FIFO drained (used before reboot).
void rv_link_flush(TickType_t timeout);
