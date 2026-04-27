cmake_minimum_required(VERSION 3.22.1)

project("ghost")

set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/dobby)

# Dobby configuration
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${DOBBY_DIR} dobby_build)

add_library(ghost SHARED
        ghost.cpp
        syscall_interceptor.cpp)

target_include_directories(ghost PRIVATE
        ${DOBBY_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(ghost
        dobby
        log
        android)
