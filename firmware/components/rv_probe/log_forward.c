// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Forward ESP_LOG output to the host as Log frames (and keep UART0 output).
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "link.h"
#include "log_forward.h"

static vprintf_like_t s_orig;
// Try-lock only: a log emitted while another task (or this one, re-entrantly)
// is forwarding is still printed on UART0 but not forwarded.
static SemaphoreHandle_t s_lock;

static retrovision_v1_LogLevel level_of(char c)
{
    switch (c) {
    case 'E': return retrovision_v1_LogLevel_LOG_LEVEL_ERROR;
    case 'W': return retrovision_v1_LogLevel_LOG_LEVEL_WARN;
    case 'I': return retrovision_v1_LogLevel_LOG_LEVEL_INFO;
    case 'D':
    case 'V': return retrovision_v1_LogLevel_LOG_LEVEL_DEBUG;
    default: return retrovision_v1_LogLevel_LOG_LEVEL_UNSPECIFIED;
    }
}

static int forward_vprintf(const char *fmt, va_list ap)
{
    va_list copy;
    va_copy(copy, ap);
    int r = s_orig ? s_orig(fmt, copy) : 0;
    va_end(copy);

    if (s_lock == NULL || xPortInIsrContext() || xSemaphoreTake(s_lock, 0) != pdTRUE) {
        return r;
    }

    char line[176];
    vsnprintf(line, sizeof line, fmt, ap);

    // Default format (colors off): "L (ms) tag: message\n"
    static retrovision_v1_Envelope env; // guarded by s_lock
    env = (retrovision_v1_Envelope)retrovision_v1_Envelope_init_zero;
    env.which_payload = retrovision_v1_Envelope_log_tag;
    retrovision_v1_Log *log = &env.payload.log;
    log->probe_ts_us = (uint64_t)esp_timer_get_time();
    log->level = level_of(line[0]);

    const char *msg = line;
    const char *close = strstr(line, ") ");
    const char *colon = close ? strstr(close + 2, ": ") : NULL;
    if (log->level != retrovision_v1_LogLevel_LOG_LEVEL_UNSPECIFIED && close && colon) {
        size_t tl = (size_t)(colon - (close + 2));
        if (tl >= sizeof log->tag) {
            tl = sizeof log->tag - 1;
        }
        memcpy(log->tag, close + 2, tl);
        log->tag[tl] = '\0';
        msg = colon + 2;
    }
    strlcpy(log->text, msg, sizeof log->text);
    size_t n = strlen(log->text);
    while (n > 0 && (log->text[n - 1] == '\n' || log->text[n - 1] == '\r')) {
        log->text[--n] = '\0';
    }
    if (n > 0) {
        (void)rv_link_send(&env, pdMS_TO_TICKS(5));
    }
    xSemaphoreGive(s_lock);
    return r;
}

void rv_log_forward_init(void)
{
    s_lock = xSemaphoreCreateMutex();
    s_orig = esp_log_set_vprintf(forward_vprintf);
}
