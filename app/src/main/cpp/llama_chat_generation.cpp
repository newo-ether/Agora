#include "llama_chat_generation.h"
#include "llama_chat_log.h"
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
    // A recurrent state only holds its last position, so pos_min is not 0 for hybrid models.
    const bool recurrent = llama_model_is_recurrent(handle->model) ||
        llama_model_is_hybrid(handle->model);
    const bool memory_matches = retained_prefix == 0
        ? pos_max == -1
        : (pos_min == 0 || recurrent) &&
            pos_max + 1 == static_cast<llama_pos>(retained_prefix);
    if (!removed || !memory_matches) {
        clear_text_cache(handle);
        return 0;
    }
    handle->decoded_tokens.resize(retained_prefix);
    return retained_prefix;
}

PromptCachePlan restore_prompt_cache(
    ChatHandle * handle,
    const std::string & template_key,
    const std::vector<llama_token> & prompt_tokens,
    int32_t & cached_tokens
) {
    namespace cache = agora::prefix_cache;
    PromptCachePlan plan;
    ChatHandle::CachedTemplate cached_template;
    if (handle->prefix_cache_dir.empty() ||
        !handle->find_template(template_key, cached_template)) return plan;

    plan.prefix_tokens = cache::prefix_token_count(
        handle->vocab, cached_template.delimiters, cached_template.fallback_prefix, prompt_tokens
    );
    if (plan.prefix_tokens > 0) {
        plan.prefix_snapshot = cache::snapshot_path(
            handle->prefix_cache_dir, handle->path, handle->n_ctx, prompt_tokens, plan.prefix_tokens
        );
        if (plan.prefix_snapshot.empty()) {
            LOGE("Prompt prefix cache disabled: cannot stat model path '%s'", handle->path.c_str());
        }
    }

    // Hybrid models cannot trim back to the shared history, so resume from the last turn's
    // checkpoint (the longest one that is still a prefix of this prompt).
    if (!cached_template.delimiters.delimiters.empty()) {
        plan.turn_tokens = cache::turn_boundary(
            handle->vocab, cached_template.delimiters, prompt_tokens
        );
        const cache::MemoryPrefix * best = nullptr;
        for (const auto & turn : handle->memory_turns) {
            if (turn.tokens.size() > static_cast<size_t>(cached_tokens) &&
                (!best || turn.tokens.size() > best->tokens.size()) &&
                cache::covers(turn, prompt_tokens, turn.tokens.size())) {
                best = &turn;
            }
        }
        if (best) {
            const size_t count = best->tokens.size();
            if (cache::restore_memory(handle->ctx, *best, prompt_tokens, count)) {
                handle->decoded_tokens.assign(prompt_tokens.begin(), prompt_tokens.begin() + count);
                cached_tokens = static_cast<int32_t>(count);
                LOGD("Restored previous turn checkpoint: %zu tokens", count);
            } else {
                handle->decoded_tokens.clear();
                cached_tokens = 0;
            }
        }
    }

    if (plan.prefix_snapshot.empty() || static_cast<size_t>(cached_tokens) >= plan.prefix_tokens) {
        return plan;
    }
    if (cache::restore_memory(handle->ctx, handle->memory_prefix, prompt_tokens, plan.prefix_tokens)) {
        LOGD("Restored prompt prefix from memory: %zu tokens", plan.prefix_tokens);
    } else if (cache::file_exists(plan.prefix_snapshot) &&
               cache::restore(handle->ctx, plan.prefix_snapshot, prompt_tokens, plan.prefix_tokens)) {
        cache::remember(handle->ctx, handle->memory_prefix, prompt_tokens, plan.prefix_tokens,
                        handle->memory_checkpoint_max_bytes);
        LOGD("Restored prompt prefix snapshot: %zu tokens", plan.prefix_tokens);
    } else {
        // A failed restore leaves the memory cleared.
        if (llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) == -1) {
            handle->decoded_tokens.clear();
            cached_tokens = 0;
        }
        plan.save_prefix = true;
        return plan;
    }
    handle->decoded_tokens.assign(prompt_tokens.begin(), prompt_tokens.begin() + plan.prefix_tokens);
    cached_tokens = static_cast<int32_t>(plan.prefix_tokens);
    return plan;
}

int32_t prompt_cache_segment_end(const PromptCachePlan & plan, int32_t offset, int32_t n_tokens) {
    if (plan.save_prefix && offset < static_cast<int32_t>(plan.prefix_tokens)) {
        return static_cast<int32_t>(plan.prefix_tokens);
    }
    if (plan.turn_tokens > 0 && offset < static_cast<int32_t>(plan.turn_tokens)) {
        return static_cast<int32_t>(plan.turn_tokens);
    }
    return n_tokens;
}

void record_prompt_cache(
    ChatHandle * handle,
    PromptCachePlan & plan,
    const std::vector<llama_token> & prompt_tokens,
    int32_t decoded
) {
    namespace cache = agora::prefix_cache;
    if (plan.turn_tokens > 0 && decoded == static_cast<int32_t>(plan.turn_tokens)) {
        cache::MemoryPrefix turn;
        cache::remember(handle->ctx, turn, prompt_tokens, plan.turn_tokens,
                        handle->memory_checkpoint_max_bytes);
        if (!turn.checkpoint.empty()) {
            handle->memory_turns.push_back(std::move(turn));
            const auto total_bytes = [&]() {
                size_t total = handle->memory_prefix.checkpoint.size();
                for (const auto & item : handle->memory_turns) total += item.checkpoint.size();
                return total;
            };
            while (handle->memory_turns.size() > ChatHandle::MAX_MEMORY_TURNS ||
                   (handle->memory_turns.size() > 1 &&
                    total_bytes() > handle->memory_total_max_bytes)) {
                handle->memory_turns.pop_front();
            }
        }
    }
    if (plan.save_prefix && decoded == static_cast<int32_t>(plan.prefix_tokens)) {
        plan.save_prefix = false;
        cache::remember(handle->ctx, handle->memory_prefix, prompt_tokens, plan.prefix_tokens,
                        handle->memory_checkpoint_max_bytes);
        if (cache::save(handle->ctx, handle->prefix_cache_dir, handle->prefix_cache_max_bytes,
                        plan.prefix_snapshot, prompt_tokens, plan.prefix_tokens)) {
            LOGD("Saved prompt prefix snapshot: %zu tokens", plan.prefix_tokens);
        } else {
            LOGD("Prompt prefix snapshot not saved: %zu tokens, state %zu bytes, budget %llu",
                 plan.prefix_tokens, llama_state_seq_get_size(handle->ctx, 0),
                 static_cast<unsigned long long>(handle->prefix_cache_max_bytes));
        }
    }
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
