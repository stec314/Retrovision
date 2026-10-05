// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// BLE observer (NimBLE): passive scanning, legacy + extended advertising.
#pragma once

#include "config.h"

void rv_ble_scanner_init(void);
void rv_ble_scanner_start(const rv_cfg_t *cfg);
void rv_ble_scanner_stop(void);
// 0 normal, 1 half scan window, 2 no scanning: called by the session on die temperature.
void rv_ble_scanner_set_throttle(int level);
