// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
#pragma once

// Mirror ESP_LOG output to the host as Log frames. Call after rv_link_init().
void rv_log_forward_init(void);
