#pragma once

#include <jni.h>
#include <string>

namespace agora {
namespace jni {

// JNI's GetStringUTFChars returns Modified UTF-8. Native templates, tokenizers and
// filesystem APIs require standard UTF-8, including four-byte supplementary code points.
inline bool read_java_string(JNIEnv * env, jstring value, std::string & result) {
    if (!value) {
        result.clear();
        return true;
    }
    jclass string_class = env->GetObjectClass(value);
    if (!string_class) return false;
    jmethodID get_bytes = env->GetMethodID(
        string_class, "getBytes", "(Ljava/lang/String;)[B"
    );
    env->DeleteLocalRef(string_class);
    if (!get_bytes) return false;
    jstring encoding = env->NewStringUTF("UTF-8");
    if (!encoding) return false;
    jbyteArray bytes = static_cast<jbyteArray>(
        env->CallObjectMethod(value, get_bytes, encoding)
    );
    env->DeleteLocalRef(encoding);
    if (env->ExceptionCheck() || !bytes) {
        if (bytes) env->DeleteLocalRef(bytes);
        return false;
    }
    const jsize size = env->GetArrayLength(bytes);
    result.resize(static_cast<size_t>(size));
    if (size > 0) {
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(&result[0]));
    }
    env->DeleteLocalRef(bytes);
    return !env->ExceptionCheck();
}

inline bool read_java_path(JNIEnv * env, jstring value, std::string & result) {
    return value && read_java_string(env, value, result) &&
        result.find('\0') == std::string::npos;
}

} // namespace jni
} // namespace agora
