#include "atomic_file_publish.h"

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <iterator>
#include <stdexcept>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

void require(bool condition, const char * message) {
    if (!condition) throw std::runtime_error(message);
}

std::string read(const std::string & path) {
    std::ifstream input(path, std::ios::binary);
    require(input.good(), "cannot open expected file");
    return std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
}

class Files {
public:
    explicit Files(const char * parent) {
        const std::string pattern = std::string(parent) + "/agora-atomic-test-XXXXXX";
        std::vector<char> buffer(pattern.begin(), pattern.end());
        buffer.push_back('\0');
        const char * directory = mkdtemp(buffer.data());
        require(directory != nullptr, "mkdtemp failed");
        root = directory;
    }

    ~Files() {
        for (const auto & path : paths) unlink(path.c_str());
        rmdir(root.c_str());
    }

    std::string path(const std::string & name) {
        const std::string result = root + "/" + name;
        paths.push_back(result);
        return result;
    }

    void write(const std::string & path, const std::string & contents) {
        std::ofstream output(path, std::ios::binary);
        output.write(contents.data(), static_cast<std::streamsize>(contents.size()));
        output.close();
        require(output.good(), "fixture write failed");
    }

private:
    std::string root;
    std::vector<std::string> paths;
};

void test(const char * parent) {
    Files files(parent);
    std::string binary(300005, '\0');
    for (size_t i = 0; i < binary.size(); ++i) binary[i] = static_cast<char>(i * 137);
    const auto source = files.path("source");
    files.write(source, binary);
    for (const auto & contents : {std::string(), binary}) {
        const auto staging = files.path(contents.empty() ? "empty-stage" : "binary-stage");
        const auto destination = files.path(contents.empty() ? "empty-target" : "binary-target");
        files.write(staging, contents);
        const int result = agora::files::publish_no_replace(staging.c_str(), destination.c_str());
        if (result != 0) {
            throw std::runtime_error("production publication failed: errno=" + std::to_string(result));
        }
        require(read(destination) == contents, "published bytes changed");
        require(access(staging.c_str(), F_OK) != 0 && errno == ENOENT, "successful rename retained staging");
        require(read(source) == binary, "source changed");
    }
    std::puts("PASS binary and empty publication; source retained");

    const auto staging = files.path("conflict-stage");
    const auto existing = files.path("existing-target");
    files.write(staging, binary);
    files.write(existing, "old");
    require(agora::files::publish_no_replace(staging.c_str(), existing.c_str()) == EEXIST,
            "existing regular file was not rejected");
    require(read(existing) == "old" && read(staging) == binary, "conflict mutated files");
    std::puts("PASS existing target conflict preserves both files");

    const auto missing = files.path("missing-target");
    const auto link = files.path("dangling-link");
    require(symlink(missing.c_str(), link.c_str()) == 0, "cannot create dangling symlink fixture");
    require(agora::files::publish_no_replace(staging.c_str(), link.c_str()) == EEXIST,
            "dangling symlink was not rejected");
    char link_value[4096];
    const auto length = readlink(link.c_str(), link_value, sizeof(link_value));
    require(length > 0 && std::string(link_value, static_cast<size_t>(length)) == missing,
            "dangling symlink changed");
    require(access(missing.c_str(), F_OK) != 0 && errno == ENOENT && read(staging) == binary,
            "symlink conflict published bytes");
    std::puts("PASS dangling symlink conflict preserves link and staging");

    const auto unicode_stage = files.path(u8"stage-\U0001F680-\u4E2D\u6587");
    const auto unicode_target = files.path(u8"target-\U0001F680-\u4E2D\u6587");
    files.write(unicode_stage, binary);
    require(agora::files::publish_no_replace(unicode_stage.c_str(), unicode_target.c_str()) == 0,
            "Unicode publication failed");
    require(read(unicode_target) == binary, "Unicode published bytes changed");
    std::puts("PASS standard UTF-8 supplementary filename");

    for (int iteration = 0; iteration < 32; ++iteration) {
        const auto left = files.path("left-" + std::to_string(iteration));
        const auto right = files.path("right-" + std::to_string(iteration));
        const auto destination = files.path("race-" + std::to_string(iteration));
        const std::string left_bytes(8193, 'L');
        const std::string right_bytes(8193, 'R');
        files.write(left, left_bytes);
        files.write(right, right_bytes);
        std::atomic<int> ready{0};
        std::atomic<bool> start{false};
        int left_result = -1;
        int right_result = -1;
        const auto publish = [&](const std::string & input, int & result) {
            ready.fetch_add(1);
            while (!start.load()) std::this_thread::yield();
            result = agora::files::publish_no_replace(input.c_str(), destination.c_str());
        };
        std::thread first(publish, std::cref(left), std::ref(left_result));
        std::thread second(publish, std::cref(right), std::ref(right_result));
        while (ready.load() != 2) std::this_thread::yield();
        start.store(true);
        first.join();
        second.join();
        require((left_result == 0 && right_result == EEXIST) ||
                (right_result == 0 && left_result == EEXIST), "race did not produce exactly one winner");
        require(read(destination) == (left_result == 0 ? left_bytes : right_bytes), "race target is incomplete");
        require(read(left_result == 0 ? right : left) == (left_result == 0 ? right_bytes : left_bytes),
                "race loser staging changed");
    }
    std::puts("PASS 32 competing publications; one complete winner each");

    const auto invalid_target = files.path("absent-parent/target");
    require(agora::files::publish_no_replace(staging.c_str(), invalid_target.c_str()) == ENOENT,
            "missing parent errno not preserved");
    require(read(staging) == binary && read(source) == binary, "failure mutated source or staging");
    std::puts("PASS error propagation preserves source and staging");
}

} // namespace

int main(int argc, char ** argv) {
    if (argc != 2) {
        std::fputs("usage: atomic_file_publish_test <existing-writable-directory>\n", stderr);
        return 2;
    }
    try {
        test(argv[1]);
        std::puts("PASS all production atomic publication checks");
        return 0;
    } catch (const std::exception & error) {
        std::fprintf(stderr, "FAIL %s\n", error.what());
        return 1;
    }
}
