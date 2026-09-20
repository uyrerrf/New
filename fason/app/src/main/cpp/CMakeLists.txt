cmake_minimum_required(VERSION 3.10.2)
project(fason_daemon CXX)

add_library(fason_daemon SHARED fason_daemon.cpp)

find_library(log-lib log)
target_link_libraries(fason_daemon ${log-lib})

# Strip symbols in release
set_target_properties(fason_daemon PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
