#pragma once

namespace agora {
namespace files {

// Returns zero on success, otherwise the captured errno. Never replaces a destination.
int publish_no_replace(const char * staging, const char * destination);

} // namespace files
} // namespace agora
