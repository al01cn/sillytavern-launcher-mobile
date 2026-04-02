cmake_minimum_required(VERSION 3.22.1)
project("sillytavern-launcher")

# ---- 日志库 ----
find_library(log-lib log)

# ============================================================
# AGP 9.x 要求 CMake 输出到 intermediates/cmake/{variant}/obj/{ABI}/
# CMAKE_BINARY_DIR = .cxx/Debug/{hash}，需要 ../intermediates/cmake/Debug/obj/{ABI}
# ============================================================
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../../build/intermediates/cmake/Debug/obj/${ANDROID_ABI})
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../../build/intermediates/cmake/Debug/obj/${ANDROID_ABI})

# ============================================================
# jniLibs 目录结构: {库名}/{ABI}/xxx.so
# ============================================================
set(LIB_ROOT ${CMAKE_SOURCE_DIR}/../jniLibs)

# ---- libnode ----
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libnode/${ANDROID_ABI}/libnode.so)

# ============================================================
# 构建 native-lib
# ============================================================
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE
    ${CMAKE_SOURCE_DIR}/include/node
)

target_link_libraries(
    native-lib
    libnode
    ${log-lib}
)

message(STATUS "=== CMake Config ===")
message(STATUS "ANDROID_ABI: ${ANDROID_ABI}")
message(STATUS "LIB_ROOT: ${LIB_ROOT}")
