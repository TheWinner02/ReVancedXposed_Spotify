cmake_minimum_required(VERSION 3.22.1)

project("revancedxposed")

set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/dobby)

# Dobby configuration
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${DOBBY_DIR} dobby_build)

add_library(revancedxposed SHARED
        native-hook.cpp)

target_include_directories(revancedxposed PRIVATE
        ${DOBBY_DIR}/include)

target_link_libraries(revancedxposed
        dobby
        log
        android)
