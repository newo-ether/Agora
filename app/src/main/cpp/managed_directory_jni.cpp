#include "managed_directory_files.h"
#include "jni_utf8.h"
#include <cerrno>

extern "C" JNIEXPORT jint JNICALL
Java_com_newoether_agora_viewmodel_ManagedDirectoryFiles_00024Native_step(
    JNIEnv * env, jobject, jstring files, jstring directory) {
    std::string files_path;
    std::string id;
    if (!agora::jni::read_java_path(env, files, files_path) ||
        !agora::jni::read_java_path(env, directory, id)) return -EINVAL;
    return agora::files::reclaim_directory_step(files_path, id);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_newoether_agora_viewmodel_ManagedDirectoryFiles_00024Native_unlink(
    JNIEnv * env, jobject, jstring files, jstring relative) {
    std::string files_path;
    std::string relative_path;
    if (!agora::jni::read_java_path(env, files, files_path) ||
        !agora::jni::read_java_path(env, relative, relative_path)) return -EINVAL;
    return agora::files::reclaim_attachment_file(files_path, relative_path);
}
