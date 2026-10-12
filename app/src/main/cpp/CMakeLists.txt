cmake_minimum_required(VERSION 3.18)
project(gozar-tun C)

add_library(gozar-tun SHARED native-tun.c)

find_library(log-lib log)
target_link_libraries(gozar-tun ${log-lib})

# اگر مسیر کتابخانهٔ استاتیک hev پاس داده شود، مستقیم لینک می‌شود
# (در ورک‌فلو: -DHEV_STATIC_LIB=<مسیر libhev-socks5-tunnel.a>)
if(HEV_STATIC_LIB AND EXISTS "${HEV_STATIC_LIB}")
  target_compile_definitions(gozar-tun PRIVATE HEV_STATIC=1)
  target_link_libraries(gozar-tun "${HEV_STATIC_LIB}")
  message(STATUS "hev linked statically: ${HEV_STATIC_LIB}")
else()
  message(STATUS "hev dynamic: runtime dlopen('libhev-socks5-tunnel.so')")
endif()
