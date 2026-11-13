cmake_minimum_required(VERSION 3.18.1)
project(clicker_uinput C)

add_library(
        clicker_uinput
        SHARED
        clicker_uinput.c)

set_target_properties(clicker_uinput PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED YES)

target_compile_options(clicker_uinput PRIVATE
        -Wall
        -Wextra
        -Werror=return-type)
