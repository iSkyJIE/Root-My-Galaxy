# DFRoot's exploit chain, built as the app's own library.
#
# Ported from https://github.com/diabl0w/DFRoot (app/src/main/jni/CMakeLists.txt).
# This is a subdirectory rather than that project's top level, so the include
# paths are written against CMAKE_CURRENT_SOURCE_DIR instead of CMAKE_SOURCE_DIR -
# and that is the only structural change. The two custom build steps and the
# `.incbin` embedding in exp.c are theirs as they are, because they are the thing
# that decides whether the assembled blobs match the symbols exp.c declares:
#
#   `splicehelper` is compiled freestanding and stripped, written *into the source
#   directory*, and embedded by exp.c's inline asm. It has to exist before the
#   assembly is assembled, which is what the dependency below is for.
#
#   The eight `.ko` images are embedded the same way, one per Android release and
#   kernel version, and exp.c picks one by reading `uname` on the phone.

set(SPLICEHELPER "${CMAKE_CURRENT_SOURCE_DIR}/splicehelper")
add_custom_command(
    OUTPUT "${SPLICEHELPER}"
    COMMAND "${CMAKE_C_COMPILER}"
            --target=${CMAKE_C_COMPILER_TARGET}
            --sysroot=${CMAKE_SYSROOT}
            "${CMAKE_CURRENT_SOURCE_DIR}/splicehelper.c"
            -o "${SPLICEHELPER}"
            -nodefaultlibs -nostartfiles -ffreestanding -static
    COMMAND "${CMAKE_STRIP}" "${SPLICEHELPER}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/splicehelper.c"
    COMMENT "Building splicehelper"
)
add_custom_target(splicehelper_bin DEPENDS "${SPLICEHELPER}")

# The `.incbin` paths inside exp.c are relative to this directory, which is why
# it is on both include lists rather than only the assembler's.
set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${CMAKE_CURRENT_SOURCE_DIR}")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -I${CMAKE_CURRENT_SOURCE_DIR} -fno-stack-protector -fomit-frame-pointer")

add_library(dfroot SHARED exp.c libcxx.S libc.S elf_parser.c)
add_dependencies(dfroot splicehelper_bin)

# Not build inputs - the `.incbin` directives already name them - but the
# dependency that makes a changed image rebuild this object. Without it a rebuilt
# .ko would be linked against a stale embedded copy, which is a test that passes
# while the phone runs the previous kernel module.
set_source_files_properties(exp.c PROPERTIES OBJECT_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android12-5.10.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android13-5.10.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android13-5.15.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android14-5.15.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android14-6.1.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android15-6.6.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android16-6.12.ko;\
${CMAKE_CURRENT_SOURCE_DIR}/ko/dirtyfrag-android17-6.18.ko;\
${SPLICEHELPER}")

target_link_libraries(dfroot log)

# The 16 KB page size the newer devices use: without this the library's segments
# are laid out for 4 KB and the loader refuses to map it.
target_link_options(dfroot PRIVATE -Wl,-z,max-page-size=16384)
