cmake_minimum_required(VERSION 3.22.1)

project("sillytavern-launcher")

# 1. 查找安卓系统库
find_library(log-lib log)

# 2. 定义【你的原生库】(native-lib)
# 必须先执行 add_library，下面的 target_include_directories 才有对象可以操作
add_library(native-lib SHARED native-lib.cpp)

# 3. 为 native-lib 配置头文件路径
# 我们一次性把 include 和 include/node 都加进去，解决之前的 v8config.h 找不到的问题
target_include_directories(native-lib PRIVATE
    ${CMAKE_SOURCE_DIR}/include/node
)

# 4. 导入【Node.js 预编译库】
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/libnode/${ANDROID_ABI}/libnode.so)

# 打印一下，看看编译时指向哪里（在构建日志里看）
message(STATUS "Current ABI: ${ANDROID_ABI}")
message(STATUS "Looking for node at: ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

# 5. 最后一步：链接库
target_link_libraries(
        native-lib
        libnode
        ${log-lib}
)