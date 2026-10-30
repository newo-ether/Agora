#pragma once

#include "llama.h"
#include "chat.h"
#include "mtmd.h"
#include "llama_prefix_cache.h"
#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace agora::chat {

struct ChatHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr chat_templates;
    std::string path;
    int32_t n_ctx = 0;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> decoded_tokens;
    mtmd_context * mtmd_ctx = nullptr;  // multimodal context (for vision models)

    // llama.cpp's PEG parser arena can no longer be serialized, so a template result carries
    // a key into this cache instead. Only the few most recent template results stay parseable.
    // The key also carries the template's message delimiters to the generation, which uses
    // them to find the prompt-cache boundaries (see llama_prefix_cache.h).
    struct CachedTemplate {
        std::shared_ptr<const common_peg_arena> parser;
        common_chat_msg_delimiters delimiters;
        std::string fallback_prefix;  // only for templates without message delimiters
    };
    std::mutex parsers_mutex;
    std::deque<std::pair<std::string, CachedTemplate>> parsers;
    uint64_t next_parser_id = 0;
    static constexpr size_t MAX_CACHED_PARSERS = 8;

    // Prompt cache. An empty directory disables the prefix snapshots.
    std::string prefix_cache_dir;
    uint64_t prefix_cache_max_bytes = agora::prefix_cache::DEFAULT_MAX_SNAPSHOT_BYTES;
    agora::prefix_cache::MemoryPrefix memory_prefix;
    // Small per-turn states, newest last: a few so that a title or other side request in
    // between does not evict the conversation's checkpoint.
    std::deque<agora::prefix_cache::MemoryPrefix> memory_turns;
    static constexpr size_t MAX_MEMORY_TURNS = 3;
    size_t memory_checkpoint_max_bytes = agora::prefix_cache::DEFAULT_MEMORY_CHECKPOINT_BYTES;
    size_t memory_total_max_bytes = agora::prefix_cache::DEFAULT_MEMORY_TOTAL_BYTES;

    std::string store_parser(
        const common_peg_arena & parser,
        const common_chat_msg_delimiters & delimiters = {},
        std::string fallback_prefix = {}
    ) {
        if (parser.empty() && delimiters.delimiters.empty() && fallback_prefix.empty()) return {};
        std::lock_guard<std::mutex> lock(parsers_mutex);
        std::string key = "agora-parser:" + std::to_string(++next_parser_id);
        parsers.emplace_back(key, CachedTemplate{
            parser.empty() ? nullptr : std::make_shared<const common_peg_arena>(parser),
            delimiters,
            std::move(fallback_prefix),
        });
        while (parsers.size() > MAX_CACHED_PARSERS) parsers.pop_front();
        return key;
    }

    // Copies under the lock: a later template result may evict the entry.
    bool find_template(const std::string & key, CachedTemplate & result) {
        std::lock_guard<std::mutex> lock(parsers_mutex);
        for (const auto & entry : parsers) {
            if (entry.first == key) {
                result = entry.second;
                return true;
            }
        }
        return false;
    }
};

} // namespace agora::chat
