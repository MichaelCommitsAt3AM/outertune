cmake_minimum_required(VERSION 3.22.1)

# Name of your native library
project("outertune-analysis")

# Define where Aubio source is
set(AUBIO_SRC "${CMAKE_CURRENT_SOURCE_DIR}/aubio/src")

# Include header files
include_directories(
        "${AUBIO_SRC}"
        "${AUBIO_SRC}/.."
)

# Filter out unsupported drivers to avoid compilation errors on Android
list(FILTER AUBIO_SOURCES EXCLUDE REGEX ".*io/source_avcodec.c")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX ".*io/source_wavread.c")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX ".*io/sink_wavwrite.c")
list(FILTER AUBIO_SOURCES EXCLUDE REGEX ".*io/audio_unit.c")

# Collect all Aubio source files (excluding specialized drivers we don't need)
file(GLOB_RECURSE AUBIO_SOURCES
        "${AUBIO_SRC}/*.c"
)

# Define the shared library we will write (JNI wrapper)
add_library(
        outertune-analysis
        SHARED
        # We will create this file in the next step
        native-lib.cpp
        ${AUBIO_SOURCES}
)

# Search for system libraries (like logging)
find_library(
        log-lib
        log
)

# Link libraries
target_link_libraries(
        outertune-analysis
        ${log-lib}
)

add_definitions(
        -DHAVE_STDLIB_H=1
        -DHAVE_STDIO_H=1
        -DHAVE_MATH_H=1
        -DHAVE_STRING_H=1
        -DHAVE_LIMITS_H=1
        -DHAVE_STDARG_H=1
        -DHAVE_ERRNO_H=1
)
