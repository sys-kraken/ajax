cmake_minimum_required(VERSION 3.22.1)
project(ajax_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp, pinned. Built static and linked into one small JNI library.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE) # portable build: never tune for the build machine
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE) # ggml's own threadpool; no libomp to ship
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b11700
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ajax_llm_core STATIC llm_core.cpp)
target_link_libraries(ajax_llm_core PUBLIC llama)

if (ANDROID)
    add_library(ajax_llm SHARED llm_jni.cpp)
    target_link_libraries(ajax_llm PRIVATE ajax_llm_core log)
else()
    # Host build (CI): the model check runs the same llm_core the phone runs.
    add_executable(ajax_eval eval_main.cpp)
    target_link_libraries(ajax_eval PRIVATE ajax_llm_core)
endif()
