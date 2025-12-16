cmake_minimum_required(VERSION 3.22.1)

project("outertune-analysis")

set(BTRACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/BTrack")
set(KISS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/kiss_fft") # New Location

# Include both directories
include_directories(
        "${BTRACK_DIR}/src"
        "${KISS_DIR}"
)

# Collect BTrack Sources
file(GLOB_RECURSE BTRACK_SOURCES
        "${BTRACK_DIR}/src/*.cpp"
)

# Collect Kiss FFT Sources (C files)
file(GLOB KISS_SOURCES
        "${KISS_DIR}/*.c"
)

add_library(
        outertune-analysis
        SHARED
        native-lib.cpp
        ${BTRACK_SOURCES}
        ${KISS_SOURCES}
)

find_library(log-lib log)

target_link_libraries(
        outertune-analysis
        ${log-lib}
)

# Definitions
add_definitions(
        -O3
        -ffast-math
        -DUSE_KISS_FFT
        -Dkiss_fft_scalar=double
)