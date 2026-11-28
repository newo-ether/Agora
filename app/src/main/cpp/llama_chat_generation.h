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
