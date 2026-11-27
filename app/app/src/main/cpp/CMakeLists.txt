cmake_minimum_required(VERSION 3.22.1)
project(riyaz LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(riyaz SHARED
    audio_engine.cpp
    jni_bridge.cpp
    render_test.cpp
    analysis.cpp
    dsp.cpp
    separation.cpp
    pitch.cpp
    mic_input.cpp
    melody.cpp
)

target_include_directories(riyaz PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

# Signalsmith is header-only and far too slow unoptimised, so optimise even debug builds.
target_compile_options(riyaz PRIVATE -O3 -Wall -Wno-unused-variable -Wno-unused-function)

target_link_libraries(riyaz oboe::oboe log android)
