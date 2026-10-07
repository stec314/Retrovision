idf_component_register(SRCS "relay_main.c"
                       REQUIRES rv_core rv_proto nanopb bt nvs_flash esp_driver_uart esp_driver_gpio
                                esp_timer esp_system esp_app_format log)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter -Wno-missing-field-initializers)
