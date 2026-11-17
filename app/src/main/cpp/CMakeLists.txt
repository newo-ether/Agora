cmake_minimum_required(VERSION 3.22)
project("agora_llama" LANGUAGES C CXX)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../thirdparty/llama.cpp" CACHE PATH "llama.cpp source directory")
get_filename_component(LLAMA_CPP_DIR "${LLAMA_CPP_DIR}" REALPATH)

add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${LLAMA_CPP_DIR}=llama.cpp
    -g0
    -fno-ident
)

set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "unknown")
set(LLAMA_INSTALL_VERSION "0.0.0")
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)
add_subdirectory(${LLAMA_CPP_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(AGORA_ANDROID_CPU_BACKENDS
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2
    )
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(AGORA_ANDROID_CPU_BACKENDS ggml-cpu-x64)
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()
foreach(backend IN LISTS AGORA_ANDROID_CPU_BACKENDS)
    if(NOT TARGET ${backend})
        message(FATAL_ERROR "Required Android CPU backend target is missing: ${backend}")
    endif()
    set_target_properties(${backend} PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    )
endforeach()

foreach(cli llama-mtmd-cli llama-mtmd-debug
         llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        set_target_properties(${cli} PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
endforeach()
foreach(cli llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        target_include_directories(${cli} PRIVATE ${LLAMA_CPP_DIR}/common)
    endif()
endforeach()
if(TARGET llama-mtmd-cli)
    target_include_directories(llama-mtmd-cli PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()
if(TARGET llama-mtmd-debug)
    target_include_directories(llama-mtmd-debug PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()

target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="unknown")

# Build llama JNI wrapper
add_library(agora_llama SHARED
    llama_jni.cpp
    llama_chat_jni.cpp
    llama_chat_callbacks.cpp
    llama_chat_template.cpp
    llama_chat_generation.cpp
)
target_link_libraries(agora_llama llama llama-common mtmd log)
target_include_directories(agora_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/tools/mtmd)
target_link_options(agora_llama PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all -Wl,--threads=1
)

# ── objcopy for reproducible builds ──────────────────────
find_program(LLVM_OBJCOPY llvm-objcopy
    HINTS ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin NO_DEFAULT_PATH
)

# ── Minimal JNI stub for proot ───────────────────────────
# System.loadLibrary("agora_proot") triggers APK extraction of native libs.
# Actual proot execution uses ProcessBuilder on libproot_exec.so (GNUmakefile-built).
# This lightweight library also owns atomic file publication without loading Llama.
add_library(agora_proot SHARED proot_jni.cpp atomic_file_jni.cpp)
target_link_options(agora_proot PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all
)

# ── Reproducible: strip .comment ─────────────────────────
if(DEFINED ENV{SOURCE_DATE_EPOCH})
    add_definitions(-DSOURCE_DATE_EPOCH=$ENV{SOURCE_DATE_EPOCH})
endif()
if(LLVM_OBJCOPY)
    add_custom_command(TARGET agora_llama POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_llama>
    )
    add_custom_command(TARGET agora_proot POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_proot>
    )
endif()
