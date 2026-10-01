# Retrovision field-test target: a device that is easy to recognise, for testing the probe and the analysis.
# Same source for both boards: idf.py set-target esp32s3 | esp32
cmake_minimum_required(VERSION 3.16)
set(COMPONENTS main)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(retrovision_target)
