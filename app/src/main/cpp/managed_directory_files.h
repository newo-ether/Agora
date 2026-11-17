#pragma once

#include <string>

namespace agora::files {
// 0: complete; 1: one bounded mutation; negative: errno, with no unsafe fallback.
int reclaim_directory_step(const std::string & files_path, const std::string & id);
// Unlink one exact leaf below sandbox-home/attachments; never follow a parent or remove a directory.
int reclaim_attachment_file(const std::string & files_path, const std::string & relative_path);
}
