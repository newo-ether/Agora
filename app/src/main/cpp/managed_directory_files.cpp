#include "managed_directory_files.h"

#include <cerrno>
#include <dirent.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
class Fd {
public:
    explicit Fd(int value) : value_(value) {}
    ~Fd() { if (value_ >= 0) close(value_); }
    int get() const { return value_; }
private:
    int value_;
};

int open_directory(int parent, const char * name) {
    return openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
}

int open_absolute(const std::string & path) {
    if (path.empty() || path.front() != '/') { errno = EINVAL; return -1; }
    int fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    size_t start = 1;
    while (fd >= 0 && start < path.size()) {
        const size_t end = path.find('/', start);
        const std::string part = path.substr(start, end == std::string::npos ? end : end - start);
        if (part.empty() || part == "." || part == "..") { close(fd); errno = EINVAL; return -1; }
        const int next = open_directory(fd, part.c_str());
        const int error = errno;
        close(fd);
        fd = next;
        errno = error;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return fd;
}

bool valid_id(const std::string & id) {
    if (id.empty() || id.size() > 128) return false;
    for (const char c : id) {
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
    }
    return true;
}

// Remove at most one leaf per call; all links are unlinked as links.
int remove_next(int parent, const char * name, int depth, int & visited) {
    if (++visited > 128 || depth > 32) return -EAGAIN;
    struct stat entry {};
    if (fstatat(parent, name, &entry, AT_SYMLINK_NOFOLLOW) != 0) return errno == ENOENT ? 0 : -errno;
    if (!S_ISDIR(entry.st_mode)) return unlinkat(parent, name, 0) == 0 ? 1 : -errno;
    Fd child(open_directory(parent, name));
    if (child.get() < 0) return -errno;
    DIR * entries = fdopendir(dup(child.get()));
    if (entries == nullptr) return -errno;
    int result = 0;
    while (true) {
        errno = 0;
        const dirent * next = readdir(entries);
        if (next == nullptr) { if (errno != 0) result = -errno; break; }
        if (std::string(next->d_name) == "." || std::string(next->d_name) == "..") continue;
        result = remove_next(child.get(), next->d_name, depth + 1, visited);
        if (result != 0) break;
    }
    closedir(entries);
    if (result != 0) return result;
    return unlinkat(parent, name, AT_REMOVEDIR) == 0 ? 1 : -errno;
}
}

int agora::files::reclaim_directory_step(const std::string & files_path, const std::string & id) {
    if (!valid_id(id)) return -EINVAL;
    Fd app(open_absolute(files_path));
    if (app.get() < 0) return -errno;
    if (mkdirat(app.get(), "attachment-reclaim", 0700) != 0 && errno != EEXIST) return -errno;
    Fd quarantine(open_directory(app.get(), "attachment-reclaim"));
    if (quarantine.get() < 0) return -errno;
    struct stat detached {};
    if (fstatat(quarantine.get(), id.c_str(), &detached, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno != ENOENT) return -errno;
        Fd home(open_directory(app.get(), "sandbox-home"));
        if (home.get() < 0) return errno == ENOENT ? 0 : -errno;
        Fd attachments(open_directory(home.get(), "attachments"));
        if (attachments.get() < 0) return errno == ENOENT ? 0 : -errno;
        struct stat source {};
        if (fstatat(attachments.get(), id.c_str(), &source, AT_SYMLINK_NOFOLLOW) != 0)
            return errno == ENOENT ? 0 : -errno;
        // A replaced root link is not proof of directory ownership. Leave it in place.
        if (!S_ISDIR(source.st_mode)) return -ELOOP;
#if defined(__NR_renameat2)
        if (syscall(__NR_renameat2, attachments.get(), id.c_str(), quarantine.get(),
                    id.c_str(), RENAME_NOREPLACE) != 0) return -errno;
#else
        return -ENOSYS;
#endif
        return 1;
    }
    int visited = 0;
    const int result = remove_next(quarantine.get(), id.c_str(), 0, visited);
    if (result == 1 && fstatat(quarantine.get(), id.c_str(), &detached, AT_SYMLINK_NOFOLLOW) != 0 &&
        errno == ENOENT) return 0;
    return result;
}

int agora::files::reclaim_attachment_file(const std::string & files_path, const std::string & relative_path) {
    // Validate the whole untrusted suffix before opening or mutating anything.
    size_t start = 0;
    int parts = 0;
    while (true) {
        const size_t end = relative_path.find('/', start);
        const std::string part = relative_path.substr(start, end == std::string::npos ? end : end - start);
        if (part.empty() || part == "." || part == ".." || part.find('\\') != std::string::npos ||
            part.find('\0') != std::string::npos || (++parts == 1 && !valid_id(part)) || parts > 34) return -EINVAL;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (parts < 2) return -EINVAL;
    Fd app(open_absolute(files_path));
    if (app.get() < 0) return -errno;
    Fd home(open_directory(app.get(), "sandbox-home"));
    if (home.get() < 0) return errno == ENOENT ? 0 : -errno;
    Fd attachments(open_directory(home.get(), "attachments"));
    if (attachments.get() < 0) return errno == ENOENT ? 0 : -errno;
    int parent = dup(attachments.get());
    if (parent < 0) return -errno;
    start = 0;
    while (true) {
        const size_t end = relative_path.find('/', start);
        if (end == std::string::npos) break;
        const std::string part = relative_path.substr(start, end - start);
        const int child = open_directory(parent, part.c_str());
        const int error = errno;
        close(parent);
        if (child < 0) return error == ENOENT ? 0 : -error;
        parent = child;
        start = end + 1;
    }
    Fd leaf_parent(parent);
    const std::string leaf = relative_path.substr(start);
    // unlinkat never follows the final link; directories fail without a recursive fallback.
    if (unlinkat(leaf_parent.get(), leaf.c_str(), 0) != 0) return errno == ENOENT ? 0 : -errno;
    return 0;
}
