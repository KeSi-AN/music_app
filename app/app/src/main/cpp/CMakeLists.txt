cmake_minimum_required(VERSION 3.22.1)
project(riyaz LANGUAGES C CXX)

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
    chroma.cpp
    melody.cpp
    transcribe.cpp
)

target_include_directories(riyaz PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

# Signalsmith is header-only and far too slow unoptimised, so optimise even debug builds.
target_compile_options(riyaz PRIVATE -O3 -Wall -Wno-unused-variable -Wno-unused-function)

# whisper.cpp (lyrics from the vocal track): CPU only, static, optimised even in debug builds.
# armv8.2-a with dotprod and fp16: every 64-bit core since ~2018 (A75/A55 on); the Edge 50 Neo has them.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory(third_party/whisper.cpp whisper EXCLUDE_FROM_ALL)
foreach(t ggml ggml-base ggml-cpu whisper)
    if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O3)
    endif()
endforeach()

target_link_libraries(riyaz oboe::oboe whisper log android)
