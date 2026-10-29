#pragma once

#include "llama.h"
#include "chat.h"
#include "mtmd.h"
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
    std::mutex parsers_mutex;
    std::deque<std::pair<std::string, std::shared_ptr<const common_peg_arena>>> parsers;
    uint64_t next_parser_id = 0;
    static constexpr size_t MAX_CACHED_PARSERS = 8;

    std::string store_parser(const common_peg_arena & parser) {
        if (parser.empty()) return {};
        std::lock_guard<std::mutex> lock(parsers_mutex);
        std::string key = "agora-parser:" + std::to_string(++next_parser_id);
        parsers.emplace_back(key, std::make_shared<const common_peg_arena>(parser));
        while (parsers.size() > MAX_CACHED_PARSERS) parsers.pop_front();
        return key;
    }

    std::shared_ptr<const common_peg_arena> find_parser(const std::string & key) {
        std::lock_guard<std::mutex> lock(parsers_mutex);
        for (const auto & entry : parsers) {
            if (entry.first == key) return entry.second;
        }
        return nullptr;
    }
};

} // namespace agora::chat
