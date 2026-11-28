#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <string>

struct common_chat_tool_call;

namespace agora::chat {

struct NativeChatCallbacks {
    jclass clazz = nullptr;
    jmethodID on_text = nullptr;
    jmethodID on_thought = nullptr;
    jmethodID on_tool_call = nullptr;
    jmethodID on_tool_calls_complete = nullptr;
    jmethodID on_done = nullptr;
    jmethodID on_error = nullptr;
};

size_t utf8_complete_prefix_len(const std::string & text);

jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len);

bool init_callbacks(JNIEnv * env, jobject callback, NativeChatCallbacks & methods);

jint report_error(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * message,
    int32_t input_tokens,
    int32_t output_tokens
);

jint report_done(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * reason,
    int32_t input_tokens,
    int32_t output_tokens
);

bool report_string(
    JNIEnv * env,
    jobject callback,
    jmethodID method,
    const std::string & value
);

bool report_tool_call(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods,
    size_t index,
    const common_chat_tool_call & call
);

bool report_tool_calls_complete(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods
);

} // namespace agora::chat
