# Probe firmware shared by all boards. Target differences are handled with
# CONFIG_IDF_TARGET_* / SOC_* checks inside the sources.
set(reqs rv_core rv_proto nanopb esp_driver_gpio esp_driver_uart
         esp_wifi esp_event nvs_flash bt esp_timer esp_system
         esp_app_format efuse soc log mbedtls)
idf_build_get_property(target IDF_TARGET)
if(NOT target STREQUAL "esp32")
    # Native USB-Serial-JTAG link and on-die temperature sensor (not on the classic ESP32).
    list(APPEND reqs esp_driver_usb_serial_jtag esp_driver_tsens)
endif()
if(target STREQUAL "esp32c5")
    # WS2812 status LED over RMT.
    list(APPEND reqs esp_driver_rmt)
endif()

idf_component_register(
    SRCS "probe_main.c" "link.c" "link_usb.c" "link_ble.c" "link_cfg.c"
         "session.c" "capture.c" "wifi_sniffer.c"
         "ble_scanner.c" "config.c" "log_forward.c"
    INCLUDE_DIRS "."
    REQUIRES ${reqs})
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter -Wno-missing-field-initializers)
