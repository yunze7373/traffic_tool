cmake_minimum_required(VERSION 3.22.1)
project(tun2socks_stub)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(tun2socks SHARED
    tun2socks_bridge.cpp
)

# Built-in generic core stub (provides tt_* symbols so user无需另行放置 so)
add_library(tun2socks_core SHARED
    core_stub.cpp
)

target_include_directories(tun2socks PRIVATE ${CMAKE_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(tun2socks ${log-lib})
target_link_libraries(tun2socks_core ${log-lib})
