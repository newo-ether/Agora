#include "llama_chat_generation.h"
#include <algorithm>

namespace agora::chat {

static constexpr int32_t PENALTY_LAST_N = 64;

void clear_text_cache(ChatHandle * handle) {
    if (handle->ctx) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
    }
    handle->decoded_tokens.clear();
}

size_t prepare_text_cache(
    ChatHandle * handle,
    const std::vector<llama_token> & prompt_tokens
) {
    size_t retained_prefix = 0;
    const size_t comparable = std::min(
        handle->decoded_tokens.size(), prompt_tokens.size()
    );
    while (retained_prefix < comparable &&
           handle->decoded_tokens[retained_prefix] == prompt_tokens[retained_prefix]) {
        retained_prefix++;
    }

    // Sampling needs logits from this request, so an exact prompt match must replay one token.
    if (retained_prefix == prompt_tokens.size() && retained_prefix > 0) {
        retained_prefix--;
    }

    llama_memory_t memory = llama_get_memory(handle->ctx);
    const bool removed = llama_memory_seq_rm(
        memory, 0, static_cast<llama_pos>(retained_prefix), -1
    );
    const llama_pos pos_min = llama_memory_seq_pos_min(memory, 0);
    const llama_pos pos_max = llama_memory_seq_pos_max(memory, 0);
    const bool memory_matches = retained_prefix == 0
        ? pos_max == -1
        : pos_min == 0 && pos_max + 1 == static_cast<llama_pos>(retained_prefix);
    if (!removed || !memory_matches) {
        clear_text_cache(handle);
        return 0;
    }
    handle->decoded_tokens.resize(retained_prefix);
    return retained_prefix;
}

bool token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & piece
) {
    char inline_buffer[256];
    int32_t length = llama_token_to_piece(
        vocab, token, inline_buffer, sizeof(inline_buffer), 0, true
    );
    if (length >= 0) {
        piece.assign(inline_buffer, static_cast<size_t>(length));
        return true;
    }
    std::vector<char> dynamic_buffer(static_cast<size_t>(-length));
    length = llama_token_to_piece(
        vocab, token, dynamic_buffer.data(), dynamic_buffer.size(), 0, true
    );
    if (length < 0) return false;
    piece.assign(dynamic_buffer.data(), static_cast<size_t>(length));
    return true;
}

common_sampler * init_chat_sampler(
    ChatHandle * handle,
    TemplateSamplingMetadata & metadata,
    float temperature,
    float top_p,
    float frequency_penalty,
    float presence_penalty,
    std::string & error
) {
    common_params_sampling params;
    params.samplers = {
        COMMON_SAMPLER_TYPE_PENALTIES,
        COMMON_SAMPLER_TYPE_MIN_P,
        COMMON_SAMPLER_TYPE_TOP_P,
        COMMON_SAMPLER_TYPE_TEMPERATURE,
    };
    params.penalty_last_n = PENALTY_LAST_N;
    params.penalty_repeat = 1.0f;
    params.penalty_freq = frequency_penalty;
    params.penalty_present = presence_penalty;
    params.min_p = 0.05f;
    params.min_keep = 1;
    params.top_p = top_p;
    params.temp = temperature;
    if (!metadata.grammar.empty()) {
        params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS, metadata.grammar };
    }
    params.grammar_lazy = metadata.grammar_lazy;
    params.generation_prompt = metadata.generation_prompt;
    for (const auto & value : metadata.preserved_tokens) {
        const auto tokens = common_tokenize(handle->vocab, value, false, true);
        if (tokens.size() == 1) {
            params.preserved_tokens.insert(tokens.front());
            metadata.preserved_token_ids.insert(tokens.front());
        }
    }
    for (const auto & source : metadata.grammar_triggers) {
        common_grammar_trigger trigger = source;
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD: {
                const auto tokens = common_tokenize(handle->vocab, trigger.value, false, true);
                if (tokens.size() == 1) {
                    if (metadata.preserved_token_ids.find(tokens.front()) ==
                        metadata.preserved_token_ids.end()) {
                        error = "Grammar trigger word is not a preserved token";
                        return nullptr;
                    }
                    trigger.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    trigger.token = tokens.front();
                }
                break;
            }
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (metadata.preserved_token_ids.find(trigger.token) ==
                    metadata.preserved_token_ids.end()) {
                    error = "Grammar trigger token is not preserved";
                    return nullptr;
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                break;
            default:
                error = "Unknown grammar trigger type";
                return nullptr;
        }
        params.grammar_triggers.push_back(std::move(trigger));
    }
    if (params.grammar_lazy && params.grammar_triggers.empty()) {
        error = "Lazy grammar requires at least one trigger";
        return nullptr;
    }
    try {
        common_sampler * sampler = common_sampler_init(handle->model, params);
        if (!sampler) error = "Unable to initialize chat sampler";
        return sampler;
    } catch (const std::exception & exception) {
        error = exception.what();
        return nullptr;
    }
}

bool is_preserved_token(
    const TemplateSamplingMetadata & metadata,
    llama_token token
) {
    return metadata.preserved_token_ids.find(token) != metadata.preserved_token_ids.end();
}

} // namespace agora::chat
