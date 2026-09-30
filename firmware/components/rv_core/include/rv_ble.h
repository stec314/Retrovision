// BLE advertisement helpers. Portable C99.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Values equal retrovision.v1.BleAddressType.
typedef enum {
    RV_BLE_ADDR_UNSPECIFIED = 0,
    RV_BLE_ADDR_PUBLIC = 1,
    RV_BLE_ADDR_RANDOM_STATIC = 2,
    RV_BLE_ADDR_RANDOM_RESOLVABLE = 3,
    RV_BLE_ADDR_RANDOM_NON_RESOLVABLE = 4,
} rv_ble_addr_type_t;

// `is_random`: TxAdd bit from the PDU (NimBLE: addr.type is RANDOM or RANDOM_ID).
// `addr_msb` is the address MSB first. Random subtype comes from the top two
// bits of the MSB (Core spec Vol 6 Part B 1.3.2): 11 static, 01 RPA, 00 NRPA.
rv_ble_addr_type_t rv_ble_classify_addr(int is_random, const uint8_t addr_msb[6]);

// Reverse a 6-byte address (controller order is LSB first).
void rv_ble_addr_reverse(const uint8_t in[6], uint8_t out[6]);

// TX power level from AD type 0x0A, or 0 when absent/malformed.
int8_t rv_ble_tx_power(const uint8_t *ad, size_t len);

#ifdef __cplusplus
}
#endif
