// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Protocol session: handshake, commands, time sync, status (docs/protocol.md §5-§8).
#pragma once

#include "retrovision.pb.h"

#define RV_PROTOCOL_MAJOR 1
#define RV_PROTOCOL_MINOR 1
#include "sdkconfig.h"

#if CONFIG_IDF_TARGET_ESP32
#define RV_PROBE_TYPE "dev.retrovision.esp32"
#else
#define RV_PROBE_TYPE "dev.retrovision.esp32s3"
#endif

void rv_session_init(void);

// Link RX callback (runs on the link RX task).
void rv_session_on_envelope(const retrovision_v1_Envelope *env);
