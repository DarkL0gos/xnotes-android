# xnotes_highlight: the vendored tree-sitter runtime, the bundled grammars and
# the host-neutral highlight API (xn_highlight.h) as one static library. The
# Android JNI bridge (app/src/main/cpp) and desktop hosts link it. Sources are
# vendored at pinned tags (see THIRD_PARTY); nothing is fetched at build time,
# keeping the F-Droid build reproducible.
cmake_minimum_required(VERSION 3.22.1)
project(xnotes_highlight C)

set(CMAKE_C_STANDARD 11)

set(GRAMMAR_SOURCES
    grammars/bash/parser.c
    grammars/bash/scanner.c
    grammars/c/parser.c
    grammars/cpp/parser.c
    grammars/cpp/scanner.c
    grammars/java/parser.c
    grammars/javascript/parser.c
    grammars/javascript/scanner.c
    grammars/json/parser.c
    grammars/kotlin/parser.c
    grammars/kotlin/scanner.c
    grammars/python/parser.c
    grammars/python/scanner.c
)

add_library(xnotes_highlight STATIC
    tree-sitter/lib/src/lib.c
    ${GRAMMAR_SOURCES}
    xn_highlight.c
)
set_target_properties(xnotes_highlight PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_include_directories(xnotes_highlight
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE tree-sitter/lib/include tree-sitter/lib/src
)

# Each grammar carries its own tree_sitter/parser.h; scope it to that grammar's
# sources so the copies never collide.
foreach(src ${GRAMMAR_SOURCES})
    get_filename_component(dir ${src} DIRECTORY)
    set_source_files_properties(${src} PROPERTIES
        INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/${dir}")
endforeach()

# Standalone build (not pulled in by another project): add a smoke test run
# against the query files the Android app ships.
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    enable_testing()
    add_executable(xn_highlight_test test/xn_highlight_test.c)
    target_link_libraries(xn_highlight_test PRIVATE xnotes_highlight)
    add_test(NAME xn_highlight_test
        COMMAND xn_highlight_test ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/assets/scm)
endif()
