#include "../managed_directory_files.h"

#include <atomic>
#include <cassert>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <unistd.h>

namespace fs = std::filesystem;
using agora::files::reclaim_directory_step;

static void put(const fs::path & path) {
    fs::create_directories(path.parent_path());
    std::ofstream(path) << "keep";
}
static void finish(const fs::path & app, const std::string & id) {
    for (int step = 0; step < 600; ++step) {
        const int result = reclaim_directory_step(app.string(), id);
        assert(result >= 0);
        if (result == 0) return;
    }
    assert(false && "cleanup did not converge");
}

int main() {
    char pattern[] = "/tmp/agora-directory-XXXXXX";
    const fs::path base = mkdtemp(pattern);
    const fs::path app = base / "app";
    const fs::path root = app / "sandbox-home/attachments";
    const fs::path outside = base / "outside";
    put(outside / "precious");
    put(root / "owned/original");
    fs::create_directory_symlink(outside, root / "owned/escape");
    fs::create_symlink(outside / "missing", root / "owned/dangling");
    assert(reclaim_directory_step(app.string(), "owned") == 1);
    // Replacing the public path after detachment must not redirect cleanup.
    fs::create_directory_symlink(outside, root / "owned");
    finish(app, "owned");
    assert(fs::exists(outside / "precious"));
    assert(fs::is_symlink(root / "owned"));
    assert(reclaim_directory_step(app.string(), "owned") == -ELOOP);

    put(root / "moved/input");
    fs::rename(root / "moved", outside / "moved");
    finish(app, "moved");
    assert(fs::exists(outside / "moved/input"));
    for (int i = 0; i < 260; ++i) put(root / "large" / std::to_string(i));
    assert(reclaim_directory_step(app.string(), "large") == 1);
    for (int i = 0; i < 128; ++i) assert(reclaim_directory_step(app.string(), "large") == 1);
    assert(fs::exists(app / "attachment-reclaim/large"));
    finish(app, "large");
    assert(!fs::exists(app / "attachment-reclaim/large"));

    // Compete an actual public-path writer against the exact production detachment/unlink calls.
    for (int round = 0; round < 100; ++round) {
        const std::string id = "race_" + std::to_string(round);
        put(root / id / "payload");
        const fs::path parked = root / (id + "_parked");
        std::atomic<bool> running {true};
        std::atomic<int> swaps {0};
        std::thread writer([&] {
            while (running.load()) {
                std::error_code error;
                fs::rename(root / id, parked, error);
                if (error) continue;
                fs::create_directory_symlink(outside, root / id, error);
                if (!error) {
                    ++swaps;
                    std::this_thread::yield();
                    fs::remove(root / id, error);
                }
                fs::rename(parked, root / id, error);
            }
        });
        while (swaps.load() == 0) std::this_thread::yield();
        for (int attempt = 0; attempt < 20; ++attempt) {
            reclaim_directory_step(app.string(), id);
            assert(fs::exists(outside / "precious"));
        }
        running = false;
        writer.join();
        assert(fs::exists(outside / "precious"));
    }

    // Exact debt uses the same no-follow traversal, without detaching or pruning its parent.
    using agora::files::reclaim_attachment_file;
    put(root / "exact/input");
    put(root / "exact/keep");
    assert(reclaim_attachment_file(app.string(), "exact/input") == 0);
    assert(!fs::exists(root / "exact/input"));
    assert(fs::exists(root / "exact/keep"));
    fs::create_symlink(outside / "precious", root / "exact/link");
    fs::create_symlink(outside / "missing", root / "exact/dangling");
    assert(reclaim_attachment_file(app.string(), "exact/link") == 0);
    assert(reclaim_attachment_file(app.string(), "exact/dangling") == 0);
    assert(fs::exists(outside / "precious"));
    fs::create_directory(root / "exact/empty");
    assert(reclaim_attachment_file(app.string(), "exact/empty") < 0);
    assert(fs::is_directory(root / "exact/empty"));
    assert(reclaim_attachment_file(app.string(), "exact/../precious") == -EINVAL);
    assert(reclaim_attachment_file(app.string(), "exact//precious") == -EINVAL);
    assert(reclaim_attachment_file(app.string(), "exact") == -EINVAL);
    assert(reclaim_attachment_file(app.string(), std::string("exact/precious\0suffix", 21)) == -EINVAL);

    put(root / "exact_parent/input");
    put(outside / "input");
    fs::rename(root / "exact_parent", root / "exact_parked");
    fs::create_directory_symlink(outside, root / "exact_parent");
    assert(reclaim_attachment_file(app.string(), "exact_parent/input") < 0);
    assert(fs::exists(outside / "input"));
    assert(fs::exists(root / "exact_parked/input"));
    fs::create_directory_symlink(outside, root / "exact/nested");
    assert(reclaim_attachment_file(app.string(), "exact/nested/input") < 0);
    assert(fs::exists(outside / "input"));

    // An actual concurrent writer repeatedly swaps an exact debt's parent for an outside link.
    for (int round = 0; round < 100; ++round) {
        const std::string id = "exact_race_" + std::to_string(round);
        put(root / id / "input");
        const fs::path parked = root / (id + "_parked");
        std::atomic<bool> running {true};
        std::atomic<int> swaps {0};
        std::thread writer([&] {
            while (running.load()) {
                std::error_code error;
                fs::rename(root / id, parked, error);
                if (error) continue;
                fs::create_directory_symlink(outside, root / id, error);
                if (!error) {
                    ++swaps;
                    std::this_thread::yield();
                    fs::remove(root / id, error);
                }
                fs::rename(parked, root / id, error);
            }
        });
        while (swaps.load() == 0) std::this_thread::yield();
        for (int attempt = 0; attempt < 40; ++attempt) {
            reclaim_attachment_file(app.string(), id + "/input");
            assert(fs::exists(outside / "input"));
            assert(fs::exists(outside / "precious"));
        }
        running = false;
        writer.join();
        assert(fs::exists(outside / "input"));
    }

    fs::rename(root, app / "safe-attachments");
    fs::create_directory_symlink(outside, root);
    assert(reclaim_directory_step(app.string(), "anything") < 0);
    assert(reclaim_attachment_file(app.string(), "exact/input") < 0);
    assert(fs::exists(outside / "precious"));
    assert(reclaim_directory_step(app.string(), "../outside") == -EINVAL);
    fs::remove_all(base);
    std::cout << "managed directory and exact file: no-follow links, moved originals, bounded resume, 100 directory and 100 exact parent races passed\n";
}
