#include "atomic_file_publish.h"
#include "jni_utf8.h"

#include <cerrno>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace agora {
namespace files {

int publish_no_replace(const char * staging, const char * destination) {
#if defined(__NR_renameat2)
    // The libc renameat2 wrapper requires API 30; the syscall keeps minSdk 26 compatible.
    const long result = syscall(__NR_renameat2, AT_FDCWD, staging,
                                AT_FDCWD, destination, RENAME_NOREPLACE);
    const int error = result == 0 ? 0 : errno;
    return error;
#else
    return ENOSYS;
#endif
}

} // namespace files
} // namespace agora

extern "C" JNIEXPORT jint JNICALL
Java_com_newoether_agora_tool_AtomicFilePublish_00024Native_renameNoReplace(
    JNIEnv * env, jobject, jstring staging, jstring destination) {
    std::string staging_path;
    std::string destination_path;
    if (!agora::jni::read_java_path(env, staging, staging_path) ||
        !agora::jni::read_java_path(env, destination, destination_path)) {
        return EINVAL;
    }
    return agora::files::publish_no_replace(staging_path.c_str(), destination_path.c_str());
}
