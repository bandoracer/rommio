cmake_minimum_required(VERSION 3.22.1)
project(rommio_native_tests LANGUAGES C)

add_library(rommio_files SHARED file_metadata.c)
target_compile_options(rommio_files PRIVATE -Wall -Wextra -Werror)
target_link_options(rommio_files PRIVATE -Wl,-z,max-page-size=16384)

option(ROMMIO_BUILD_TEST_CORE "Build the synthetic persistence test core in debug only" OFF)
if(ROMMIO_BUILD_TEST_CORE)
    add_library(rommio_test_core SHARED ../../debug/cpp/persistence_test_core.c)
    target_compile_options(rommio_test_core PRIVATE -Wall -Wextra -Werror)
    target_link_options(rommio_test_core PRIVATE -Wl,-z,max-page-size=16384)
endif()
