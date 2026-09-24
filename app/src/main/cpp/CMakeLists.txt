# libxnotests: the thin JNI bridge over native/highlight (the vendored
# tree-sitter runtime, the bundled grammars and the host-neutral highlight API),
# compiled as one small shared library.
cmake_minimum_required(VERSION 3.22.1)
project(xnotests C)

set(CMAKE_C_STANDARD 11)
# Applied to every build type (AGP release uses RelWithDebInfo, not Release), and
# to the highlight library below since it is added after this line.
add_compile_options(-Os -fvisibility=hidden -ffunction-sections -fdata-sections)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/highlight
                 ${CMAKE_CURRENT_BINARY_DIR}/highlight)

add_library(xnotests SHARED ts_jni.c)
target_link_libraries(xnotests PRIVATE xnotes_highlight)

# No build-id note: it hashes the pre-strip output (build paths included), so it
# varies across build hosts and breaks F-Droid's reproducible-build verification.
# max-page-size=16384 keeps the lib loadable on 16 KB page-size devices.
target_link_options(xnotests PRIVATE -Wl,--gc-sections -Wl,--build-id=none
    -Wl,-z,max-page-size=16384)
