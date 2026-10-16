cmake_minimum_required(VERSION 3.22)
project("agora_native" LANGUAGES C CXX)

add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -g0
    -fno-ident
)

# ── Minimal JNI stub for proot ───────────────────────────
# System.loadLibrary("agora_proot") triggers APK extraction of native libs.
# Actual proot execution uses ProcessBuilder on libproot_exec.so (GNUmakefile-built).
add_library(agora_proot SHARED proot_jni.cpp)
target_link_options(agora_proot PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all
)

# ── Reproducible: strip .comment ─────────────────────────
find_program(LLVM_OBJCOPY llvm-objcopy
    HINTS ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin NO_DEFAULT_PATH
)
if(DEFINED ENV{SOURCE_DATE_EPOCH})
    add_definitions(-DSOURCE_DATE_EPOCH=$ENV{SOURCE_DATE_EPOCH})
endif()
if(LLVM_OBJCOPY)
    add_custom_command(TARGET agora_proot POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_proot>
    )
endif()
