#pragma once

#include "llama.h"
#include "chat.h"
#include "mtmd.h"
#include <atomic>
#include <string>
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
};

} // namespace agora::chat
