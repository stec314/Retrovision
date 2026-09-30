// Protocol session: handshake, commands, time sync, status (docs/protocol.md §5-§8).
#pragma once

#include "retrovision.pb.h"

#define RV_PROTOCOL_MAJOR 1
#define RV_PROTOCOL_MINOR 0
#define RV_PROBE_TYPE "dev.retrovision.esp32s3"

void rv_session_init(void);

// Link RX callback (runs on the link RX task).
void rv_session_on_envelope(const retrovision_v1_Envelope *env);
