#include "llama_chat_callbacks.h"
#include "chat.h"
#include <cstring>
#include <vector>

namespace agora::chat {

// Returns the byte length of the largest prefix of `text` that ends on a
// complete UTF-8 character boundary. llama frequently splits a multi-byte glyph
// (CJK, Arabic/Persian, emoji, …) across token pieces, so a single piece may end
// with a truncated sequence. Handing those raw bytes to NewStringUTF aborts the
// VM ("input is not valid Modified UTF-8"), so callers buffer the incomplete tail
// until the next token completes it.
size_t utf8_complete_prefix_len(const std::string & text) {
    size_t len = text.length();
    // A truncated lead byte can only be within the last 3 bytes of the buffer.
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(text[len - i]);
        if ((c & 0xE0) == 0xC0) return i < 2 ? len - i : len; // 2-byte sequence
        if ((c & 0xF0) == 0xE0) return i < 3 ? len - i : len; // 3-byte sequence
        if ((c & 0xF8) == 0xF0) return i < 4 ? len - i : len; // 4-byte sequence
        // ASCII or continuation byte: keep scanning back for the lead byte.
    }
    return len;
}

// Build a jstring from standard UTF-8 bytes WITHOUT going through NewStringUTF.
// NewStringUTF expects *Modified* UTF-8, in which supplementary-plane code points
// (U+10000+ — emoji, CJK extensions) must be a 6-byte CESU-8 surrogate pair; a
// standard 4-byte UTF-8 sequence is invalid Modified UTF-8 and aborts the VM. We
// decode UTF-8 → UTF-16 (emitting surrogate pairs) and use NewString, which takes
// genuine UTF-16 and handles the whole BMP + supplementary range safely.
jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len) {
    std::vector<jchar> utf16;
    utf16.reserve(len);
    size_t i = 0;
    while (i < len) {
        unsigned char c = static_cast<unsigned char>(data[i]);
        uint32_t cp;
        size_t adv;
        if (c < 0x80) {
            cp = c; adv = 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            cp = (uint32_t(c & 0x1F) << 6) | (data[i + 1] & 0x3F); adv = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            cp = (uint32_t(c & 0x0F) << 12) | (uint32_t(data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F); adv = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            cp = (uint32_t(c & 0x07) << 18) | (uint32_t(data[i + 1] & 0x3F) << 12)
               | (uint32_t(data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F); adv = 4;
        } else {
            cp = 0xFFFD; adv = 1; // malformed lead/continuation → replacement char
        }
        i += adv;
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}


bool init_callbacks(JNIEnv * env, jobject callback, NativeChatCallbacks & methods) {
    methods.clazz = env->GetObjectClass(callback);
    if (!methods.clazz) return false;
    methods.on_text = env->GetMethodID(methods.clazz, "onText", "(Ljava/lang/String;)Z");
    methods.on_thought = env->GetMethodID(
        methods.clazz, "onThought", "(Ljava/lang/String;)Z"
    );
    methods.on_tool_call = env->GetMethodID(
        methods.clazz, "onToolCall",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"
    );
    methods.on_tool_calls_complete = env->GetMethodID(
        methods.clazz, "onToolCallsComplete", "()Z"
    );
    methods.on_done = env->GetMethodID(methods.clazz, "onDone", "(Ljava/lang/String;II)V");
    methods.on_error = env->GetMethodID(methods.clazz, "onError", "(Ljava/lang/String;II)V");
    if (methods.on_text && methods.on_thought && methods.on_tool_call &&
        methods.on_tool_calls_complete && methods.on_done && methods.on_error) return true;
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return false;
}

jint report_error(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * message,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jmessage = utf8_to_jstring(env, message, std::strlen(message));
    env->CallVoidMethod(
        callback, methods.on_error, jmessage,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jmessage);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return -1;
}

jint report_done(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * reason,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jreason = env->NewStringUTF(reason);
    env->CallVoidMethod(
        callback, methods.on_done, jreason,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jreason);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return output_tokens;
}

bool report_string(
    JNIEnv * env,
    jobject callback,
    jmethodID method,
    const std::string & value
) {
    jstring jvalue = utf8_to_jstring(env, value.data(), value.size());
    jboolean accepted = env->CallBooleanMethod(callback, method, jvalue);
    env->DeleteLocalRef(jvalue);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

bool report_tool_call(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods,
    size_t index,
    const common_chat_tool_call & call
) {
    jstring jid = utf8_to_jstring(env, call.id.data(), call.id.size());
    jstring jname = utf8_to_jstring(env, call.name.data(), call.name.size());
    jstring jarguments = utf8_to_jstring(
        env, call.arguments.data(), call.arguments.size()
    );
    jboolean accepted = env->CallBooleanMethod(
        callback, methods.on_tool_call, static_cast<jint>(index), jid, jname, jarguments
    );
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jarguments);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

bool report_tool_calls_complete(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods
) {
    jboolean accepted = env->CallBooleanMethod(callback, methods.on_tool_calls_complete);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

} // namespace agora::chat
