#pragma once

#include "llama_chat_handle.h"
#include "llama_chat_template.h"
#include "sampling.h"
#include <cstddef>

namespace agora::chat {

static constexpr int32_t CALLBACK_TOKEN_BATCH = 4;
static constexpr size_t CALLBACK_BYTE_BATCH = 64;

void clear_text_cache(ChatHandle * handle);

size_t prepare_text_cache(
    ChatHandle * handle,
    const std::vector<llama_token> & prompt_tokens
);

// Prompt cache for one request: where the conversation-independent prefix and the current
// turn's reply start lie, and whether the prefix snapshot still has to be written.
struct PromptCachePlan {
    size_t prefix_tokens = 0;
    std::string prefix_snapshot;
    bool save_prefix = false;
    size_t turn_tokens = 0;
};

// Restores the longest cached state beyond `cached_tokens` (a turn checkpoint, the RAM
// prefix, then the disk snapshot) and plans what to record during this prefill.
PromptCachePlan restore_prompt_cache(
    ChatHandle * handle,
    const std::string & template_key,
    const std::vector<llama_token> & prompt_tokens,
    int32_t & cached_tokens
);

// The prefill must stop exactly at each boundary it records.
int32_t prompt_cache_segment_end(const PromptCachePlan & plan, int32_t offset, int32_t n_tokens);

// Call after each prefill chunk; records the boundary the context has just reached.
void record_prompt_cache(
    ChatHandle * handle,
    PromptCachePlan & plan,
    const std::vector<llama_token> & prompt_tokens,
    int32_t decoded
);

bool token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & piece
);

common_sampler * init_chat_sampler(
    ChatHandle * handle,
    TemplateSamplingMetadata & metadata,
    float temperature,
    float top_p,
    float frequency_penalty,
    float presence_penalty,
    std::string & error
);

bool is_preserved_token(
    const TemplateSamplingMetadata & metadata,
    llama_token token
);

} // namespace agora::chat
