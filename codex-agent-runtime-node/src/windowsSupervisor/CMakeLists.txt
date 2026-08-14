cmake_minimum_required(VERSION 3.25)

cmake_policy(SET CMP0091 NEW)
project(codex_agent_node_windows_supervisor LANGUAGES C)

if(NOT MSVC)
    message(FATAL_ERROR "The Windows supervisor must be built with MSVC")
endif()
if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "The Windows supervisor must be built for x64")
endif()
if(NOT DEFINED CODEX_AGENT_SUPERVISOR_OUTPUT_DIR)
    message(FATAL_ERROR "CODEX_AGENT_SUPERVISOR_OUTPUT_DIR is required")
endif()

add_executable(codex_agent_node_windows_supervisor supervisor.c)
set_target_properties(codex_agent_node_windows_supervisor PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED YES
    C_EXTENSIONS NO
    MSVC_RUNTIME_LIBRARY MultiThreaded
    OUTPUT_NAME codex-agent-node-windows-supervisor
    RUNTIME_OUTPUT_DIRECTORY "${CODEX_AGENT_SUPERVISOR_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CODEX_AGENT_SUPERVISOR_OUTPUT_DIR}"
)
target_compile_definitions(codex_agent_node_windows_supervisor PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
    WINVER=0x0A00
)
target_compile_options(codex_agent_node_windows_supervisor PRIVATE
    /W4
    /WX
    /sdl
    /guard:cf
    /utf-8
    /Brepro
)
target_link_options(codex_agent_node_windows_supervisor PRIVATE
    /Brepro
    /INCREMENTAL:NO
    /DYNAMICBASE
    /NXCOMPAT
    /HIGHENTROPYVA
    /guard:cf
    /CETCOMPAT
)
