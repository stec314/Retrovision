// BLE observer (NimBLE): passive scanning, legacy + extended advertising.
#pragma once

#include "config.h"

void rv_ble_scanner_init(void);
void rv_ble_scanner_start(const rv_cfg_t *cfg);
void rv_ble_scanner_stop(void);
