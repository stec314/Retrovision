# Retrovision probe firmware for classic ESP32 boards (NodeMCU-32S, DevKitC, WROOM-32) over the USB-UART bridge.
cmake_minimum_required(VERSION 3.16)

# Shared, target-independent components (nanopb, generated proto, rv_core).
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../components")
# Keep the build lean: only pull in what main/ actually requires.
set(COMPONENTS main)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(retrovision_esp32)
