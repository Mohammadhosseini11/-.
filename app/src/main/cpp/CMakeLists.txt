cmake_minimum_required(VERSION 3.18)
project(gozar-tun C)

# مخزن hev-socks5-tunnel در ریشهٔ ریپو کلون می‌شود (گام ۸)
set(HEV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hev-socks5-tunnel")

add_subdirectory(${HEV_DIR} hev-build)

add_library(gozar-tun SHARED native-tun.c)

find_library(log-lib log)
target_link_libraries(gozar-tun hev-socks5-tunnel ${log-lib})