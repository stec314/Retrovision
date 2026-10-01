// 2.4 GHz Wi-Fi sniffer: promiscuous RX + channel hopping. Receive-only.
#pragma once

#include "config.h"

void rv_wifi_sniffer_init(void);
void rv_wifi_sniffer_start(const rv_cfg_t *cfg);
void rv_wifi_sniffer_stop(void);
uint8_t rv_wifi_sniffer_channel(void);
