# Portable core: no ESP-IDF APIs, host-tested (see test/).
idf_component_register(SRCS "rv_framing.c" "rv_wifi.c" "rv_ble.c" "rv_dedup.c"
                       INCLUDE_DIRS "include")
