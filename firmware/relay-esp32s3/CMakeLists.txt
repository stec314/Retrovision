# Retrovision BLE relay for a Seeed Studio XIAO ESP32-S3 wired to a probe's UART (see README.md).
cmake_minimum_required(VERSION 3.16)

# Shared components: rv_core (framing), rv_proto + nanopb (to read the probe's Hello).
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../components")
set(COMPONENTS main)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(retrovision_relay_esp32s3)
