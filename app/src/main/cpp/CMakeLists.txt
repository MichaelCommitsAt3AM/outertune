cmake_minimum_required(VERSION 3.22.1)

project("outertune-analysis")

set(AUBIO_SRC "${CMAKE_CURRENT_SOURCE_DIR}/aubio/src")

include_directories(
        "${AUBIO_SRC}"
        "${AUBIO_SRC}/.."
)

# Collect all Aubio source files EXCEPT io directory
file(GLOB_RECURSE AUBIO_SOURCES
        "${AUBIO_SRC}/cvec.c"
        "${AUBIO_SRC}/fvec.c"
        "${AUBIO_SRC}/fmat.c"
        "${AUBIO_SRC}/lvec.c"
        "${AUBIO_SRC}/vecutils.c"
        "${AUBIO_SRC}/mathutils.c"
        "${AUBIO_SRC}/musicutils.c"
        "${AUBIO_SRC}/temporal/*.c"
        "${AUBIO_SRC}/spectral/*.c"
        "${AUBIO_SRC}/pitch/*.c"
        "${AUBIO_SRC}/onset/*.c"
        "${AUBIO_SRC}/tempo/*.c"
        "${AUBIO_SRC}/notes/*.c"
        "${AUBIO_SRC}/utils/*.c"
        "${AUBIO_SRC}/synth/wavetable.c"
        "${AUBIO_SRC}/effects/*.c"
)

# Log what we're compiling (for debugging)
list(LENGTH AUBIO_SOURCES NUM_AUBIO_SOURCES)
message(STATUS "Found ${NUM_AUBIO_SOURCES} Aubio source files")


add_library(
        outertune-analysis
        SHARED
        native-lib.cpp
        ${AUBIO_SOURCES}
)

find_library(log-lib log)

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
        -DHAVE_UNISTD_H=1
        -DHAVE_C99_VARARGS_MACROS=1
)
