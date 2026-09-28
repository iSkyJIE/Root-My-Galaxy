cmake_minimum_required(VERSION 3.22)
project(exp)

enable_language(ASM)

set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${CMAKE_SOURCE_DIR}")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${C_FLAGS} -I${CMAKE_SOURCE_DIR}  -Wall -Wextra -Wmost -fno-stack-protector -fomit-frame-pointer")

add_library(exp SHARED exp.c stage1.S elf_parser.c)

set_source_files_properties(exp.c PROPERTIES
    OBJECT_DEPENDS "${CMAKE_SOURCE_DIR}/dirtyfrag-android12-5.10.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android13-5.10.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android13-5.15.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android14-5.15.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android14-6.1.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android15-6.6.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android16-6.12.ko;${CMAKE_SOURCE_DIR}/dirtyfrag-android17-6.18.ko;${CMAKE_SOURCE_DIR}/splicehelper")

target_link_libraries(exp log)

# The verified exec: a launcher that opens a file once, hashes *that descriptor*, rewinds it and gives the
# same descriptor to `execveat(AT_EMPTY_PATH)`. Hashing a pathname and exec'ing it later leaves a window in
# which the file can be replaced, and this is the fix for exactly that - vendored from DFReroot-S25U with its
# minimal SHA-256.
#
# An executable rather than part of `exp`, because that is what upstream's file is: a `main()` that either
# execs the candidate or exits with a code. Building it here is what keeps it compilable against this tree;
# getting it onto the phone and into a stage command is the separate step, since an executable target is not
# packaged into the APK the way a library's `.so` is.
add_executable(dfr_verified_exec dfr_verified_exec.c sha256.c)
