idf_component_register(
    SRCS "app_main.c" "link.c" "session.c" "capture.c" "wifi_sniffer.c"
         "ble_scanner.c" "config.c" "log_forward.c"
    INCLUDE_DIRS "."
    REQUIRES rv_core rv_proto nanopb
             esp_driver_usb_serial_jtag esp_driver_gpio esp_driver_tsens
             esp_wifi esp_event nvs_flash bt esp_timer esp_system
             esp_app_format efuse soc log)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter -Wno-missing-field-initializers)
