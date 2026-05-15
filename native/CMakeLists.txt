cmake_minimum_required(VERSION 3.22.1)
project("aicustomer")

set(CMAKE_CXX_STANDARD 17)

# ============ llama.cpp 集成 ============
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp_build)

# ============ LLM Native Bridge ============
add_library(llm_bridge SHARED llm_bridge.cpp)

target_include_directories(llm_bridge PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/ggml/src
    ${MTMD_DIR})

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(llm_bridge
    ${log-lib}
    ${android-lib}
    llama
    llama-common)
