# Retrovision probe firmware for Seeed Studio XIAO ESP32-S3.
cmake_minimum_required(VERSION 3.16)

# Shared, target-independent components (nanopb, generated proto, rv_core).
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../components")
# Keep the build lean: only pull in what main/ actually requires.
set(COMPONENTS main)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(retrovision_esp32s3)
