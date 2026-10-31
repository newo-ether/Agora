#include <jni.h>
#include <algorithm>
#include <chrono>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include "llama.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "jni_utf8.h"
#include "llama_chat_callbacks.h"
#include "llama_chat_generation.h"
#include "llama_chat_handle.h"
#include "llama_chat_log.h"
#include "llama_chat_parser.h"
#include "llama_chat_template.h"

using agora::chat::ChatHandle;
using agora::chat::NativeChatCallbacks;
using agora::chat::NativeChatParser;
using agora::chat::TemplateSamplingMetadata;
using agora::chat::CALLBACK_TOKEN_BATCH;
using agora::chat::CALLBACK_BYTE_BATCH;
using agora::chat::clear_text_cache;
using agora::chat::prepare_text_cache;
using agora::chat::PromptCachePlan;
using agora::chat::restore_prompt_cache;
using agora::chat::prompt_cache_segment_end;
using agora::chat::record_prompt_cache;
using agora::chat::token_to_piece;
using agora::chat::init_chat_sampler;
using agora::chat::is_preserved_token;
using agora::chat::init_callbacks;
using agora::chat::read_template_metadata;
using agora::chat::report_error;
using agora::chat::report_done;
using agora::chat::utf8_complete_prefix_len;
using agora::jni::read_java_path;
using agora::jni::read_java_string;

static bool abort_callback(void * data) {
    ChatHandle * handle = (ChatHandle *)data;
    return handle->cancelled.load(std::memory_order_relaxed);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadModel(
    JNIEnv * env, jclass /*clazz*/, jstring path, jint n_ctx) {

    std::string path_str;
    if (!read_java_path(env, path, path_str)) return 0;

    ChatHandle * handle = new ChatHandle();
    if (!handle) {
        return 0;
    }

    const auto load_started = std::chrono::steady_clock::now();
    llama_model_params model_params = llama_model_default_params();
    const auto model_started = std::chrono::steady_clock::now();
    handle->model = llama_model_load_from_file(path_str.c_str(), model_params);
    handle->path = path_str;  // keys the prompt-prefix snapshots
    const auto model_finished = std::chrono::steady_clock::now();

    if (!handle->model) {
        LOGE("Failed to load model from file");
        delete handle;
        return 0;
    }

    handle->vocab = llama_model_get_vocab(handle->model);
    handle->n_ctx = n_ctx;

    try {
        handle->chat_templates = common_chat_templates_init(handle->model, "");
        if (!common_chat_templates_was_explicit(handle->chat_templates.get())) {
            handle->chat_templates.reset();
            LOGE("Model does not contain an explicit chat template");
        }
    } catch (const std::exception &) {
        handle->chat_templates.reset();
        LOGE("Failed to initialize model chat template");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = n_ctx;
    // n_batch bounds the LOGITS/EMBEDDINGS buffers llama.cpp allocates up front, so tying it to
    // n_ctx made memory grow with the square of the context — the OOM on large-context local
    // models (#53). 512 is llama.cpp's own default and prefill is chunked to match; on-device
    // prompt-eval throughput is unaffected because it is compute-bound well below 512 tokens.
    ctx_params.n_batch = std::min(512, n_ctx);

    const auto context_started = std::chrono::steady_clock::now();
    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (!handle->ctx) {
        LOGE("Failed to create context");
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }

    llama_set_abort_callback(handle->ctx, abort_callback, handle);

    const auto load_finished = std::chrono::steady_clock::now();
    const auto model_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        model_finished - model_started
    ).count();
    const auto context_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - context_started
    ).count();
    const auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - load_started
    ).count();
    LOGD("Chat load: model_ms=%lld, context_ms=%lld, total_ms=%lld, n_ctx=%d, n_ctx_train=%d",
         (long long)model_ms, (long long)context_ms, (long long)total_ms,
         n_ctx, llama_model_n_ctx_train(handle->model));

    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobject template_result, jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) return report_error(
        env, callback, callbacks, "Model is not loaded", 0, 0
    );

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    TemplateSamplingMetadata metadata;
    if (!read_template_metadata(env, template_result, metadata)) {
        return report_error(env, callback, callbacks, "Unable to read chat template", 0, 0);
    }
    const std::string & prompt_text = metadata.prompt;

    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    int32_t n_tokens_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                       prompt_text.size(), tokens.data(),
                                       n_tokens_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                   prompt_text.size(), tokens.data(),
                                   -n_tokens, true, true);
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization returned 0 tokens for prompt len=%zu", prompt_text.size());
        return report_error(env, callback, callbacks, "Tokenization failed", 0, 0);
    }
    tokens.resize(n_tokens);

    const int32_t n_ctx = llama_n_ctx(handle->ctx);
    const int32_t min_generation_room = 4;
    if (n_tokens + min_generation_room > n_ctx) {
        LOGE("Prompt too long: prompt=%d + reserved=%d > ctx=%d",
             n_tokens, min_generation_room, n_ctx);
        char error_msg[64];
        std::snprintf(error_msg, sizeof(error_msg),
                      "LOCAL_CONTEXT_EXCEEDED:%d:%d", n_tokens, n_ctx);
        return report_error(env, callback, callbacks, error_msg, n_tokens, 0);
    }

    LOGD("Generating: prompt_len=%zu, n_tokens=%d, max_tokens=%d",
         prompt_text.size(), n_tokens, max_tokens);

    std::string sampler_error;
    common_sampler * smpl = init_chat_sampler(
        handle, metadata, temperature, top_p,
        frequency_penalty, presence_penalty, sampler_error
    );
    if (!smpl) {
        const char * message = sampler_error.empty()
            ? "Unable to initialize chat sampler"
            : sampler_error.c_str();
        return report_error(env, callback, callbacks, message, 0, 0);
    }

    int32_t cached_tokens = static_cast<int32_t>(prepare_text_cache(handle, tokens));
    PromptCachePlan cache_plan = restore_prompt_cache(handle, metadata.parser, tokens, cached_tokens);
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(handle->ctx));
    int32_t input_tokens = cached_tokens;
    const auto prefill_started = std::chrono::steady_clock::now();
    for (int32_t off = cached_tokens; off < n_tokens;) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Cancelled during prefill at %d/%d tokens", off, n_tokens);
            common_sampler_free(smpl);
            return report_done(env, callback, callbacks, "cancelled", input_tokens, 0);
        }
        const int32_t chunk = std::min(
            n_batch, prompt_cache_segment_end(cache_plan, off, n_tokens) - off
        );
        llama_batch batch = llama_batch_get_one(tokens.data() + off, chunk);
        const int32_t decode_result = llama_decode(handle->ctx, batch);
        if (decode_result != 0) {
            const bool was_cancelled =
                handle->cancelled.load(std::memory_order_relaxed);
            LOGE("Prefill decode failed at offset %d (chunk=%d, code=%d)",
                 off, chunk, decode_result);
            clear_text_cache(handle);
            common_sampler_free(smpl);
            if (was_cancelled) {
                return report_done(
                    env, callback, callbacks, "cancelled", input_tokens, 0
                );
            }
            return report_error(
                env, callback, callbacks, "Prefill decode failed", input_tokens, 0
            );
        }
        handle->decoded_tokens.insert(
            handle->decoded_tokens.end(), tokens.begin() + off, tokens.begin() + off + chunk
        );
        input_tokens += chunk;
        off += chunk;
        record_prompt_cache(handle, cache_plan, tokens, off);
    }
    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Text prefill: input_tokens=%d, cached_tokens=%d, processed_tokens=%d, duration_ms=%lld",
         input_tokens, cached_tokens, input_tokens - cached_tokens,
         (long long)prefill_ms);

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    NativeChatParser parser(metadata, *handle);
    const char * stop_reason = nullptr;
    std::string failure;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Generation cancelled at %d tokens", generated);
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            LOGD("Context full at %d tokens", generated);
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = common_sampler_sample(smpl, handle->ctx, -1);
        common_sampler_accept(smpl, new_token_id, true);

        if (llama_vocab_is_eog(handle->vocab, new_token_id) &&
            !is_preserved_token(metadata, new_token_id)) {
            LOGD("EOG token %d at position %d", new_token_id, generated);
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            LOGE("llama_token_to_piece failed");
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        const int32_t decode_result = llama_decode(handle->ctx, single);
        if (decode_result != 0) {
            const bool was_cancelled =
                handle->cancelled.load(std::memory_order_relaxed);
            LOGE("Decode failed at token %d (code=%d)", generated + 1, decode_result);
            clear_text_cache(handle);
            if (was_cancelled) stop_reason = "cancelled";
            else failure = "Decode failed";
            break;
        }
        handle->decoded_tokens.push_back(new_token_id);
        generated++;
        parser.add_token(piece, new_token_id);

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!parser.update(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len, true, failure
                )) {
                if (failure.empty()) failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (failure.empty() && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!parser.update(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size(), true, failure
            )) {
            if (failure.empty()) failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (failure.empty() && !utf8_pending.empty()) {
        failure = "Generated incomplete UTF-8 output";
    }
    if (failure.empty() && !consumer_closed && stop_reason &&
        std::strcmp(stop_reason, "cancelled") != 0 &&
        !parser.finish(env, callback, callbacks, failure)) {
        if (failure.empty()) failure = "Stream consumer closed";
        consumer_closed = true;
    }
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Text decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure.empty() ? stop_reason : "error");
    LOGD("Text request: input_tokens=%d, output_tokens=%d, total_ms=%lld",
         input_tokens, generated, (long long)request_ms);
    common_sampler_free(smpl);
    if (!failure.empty()) {
        return report_error(
            env, callback, callbacks, failure.c_str(), input_tokens, generated
        );
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadMmproj(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring mmproj_path) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return JNI_FALSE;

    std::string mmproj_str;
    if (!read_java_path(env, mmproj_path, mmproj_str)) return JNI_FALSE;

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = 4;
    params.print_timings = false;

    // Try loading new mmproj first (don't free old one yet)
    mtmd_context * new_mtmd = mtmd_init_from_file(mmproj_str.c_str(), handle->model, params);

    if (!new_mtmd) {
        LOGE("Failed to load mmproj, keeping previous if any");
        return JNI_FALSE;
    }

    // Success: free old, install new
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
    }
    handle->mtmd_ctx = new_mtmd;

    LOGD("mmproj loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatUnloadMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
        handle->mtmd_ctx = nullptr;
        LOGD("mmproj unloaded");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatHasMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    return handle->mtmd_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerateWithImages(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobject template_result, jobjectArray image_paths,
    jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) {
        return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    }
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) {
        return report_error(env, callback, callbacks, "Model is not loaded", 0, 0);
    }
    if (!handle->mtmd_ctx) {
        return report_error(
            env, callback, callbacks,
            "Vision projector not loaded. Add mmproj file in model settings.", 0, 0
        );
    }

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    TemplateSamplingMetadata metadata;
    if (!read_template_metadata(env, template_result, metadata)) {
        return report_error(env, callback, callbacks, "Unable to read chat template", 0, 0);
    }
    const std::string & prompt_text = metadata.prompt;
    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    const auto prefill_started = std::chrono::steady_clock::now();

    // --- Build bitmaps from image paths ---
    jint n_images = env->GetArrayLength(image_paths);
    std::vector<mtmd_bitmap *> bitmaps(n_images, nullptr);
    std::vector<std::string> image_path_storage(n_images);

    for (jint i = 0; i < n_images; i++) {
        jstring jpath = (jstring)env->GetObjectArrayElement(image_paths, i);
        if (!jpath) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        if (!read_java_path(env, jpath, image_path_storage[i])) {
            env->DeleteLocalRef(jpath);
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        env->DeleteLocalRef(jpath);

        const auto loaded = mtmd_helper_bitmap_init_from_file(
            handle->mtmd_ctx, image_path_storage[i].c_str(), false,
            mtmd_helper_init_opt_default()
        );
        // Agora sends still images only; drop any video helper the loader created.
        if (loaded.video_ctx) mtmd_helper_video_free(loaded.video_ctx);
        bitmaps[i] = loaded.bitmap;
        if (!bitmaps[i]) {
            LOGE("Failed to load image at index %d of %d", i, n_images);
            // Clean up already-loaded bitmaps
            for (jint j = 0; j < i; j++) {
                if (bitmaps[j]) mtmd_bitmap_free(bitmaps[j]);
            }
            return report_error(
                env, callback, callbacks, "Failed to load image for multimodal input.", 0, 0
            );
        }
    }

    // --- Tokenize prompt with image markers ---
    mtmd_input_text text_input{};
    text_input.text         = prompt_text.c_str();
    text_input.text_len     = prompt_text.size();
    text_input.add_special  = true;
    text_input.parse_special = true;

    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (auto & b : bitmaps) bitmap_ptrs.push_back(b);

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        return report_error(
            env, callback, callbacks, "Unable to allocate multimodal prompt chunks.", 0, 0
        );
    }
    int32_t tok_ret = mtmd_tokenize(handle->mtmd_ctx, chunks, &text_input,
                                    bitmap_ptrs.data(), bitmap_ptrs.size());
    if (tok_ret != 0) {
        LOGE("mtmd_tokenize failed with code %d (images=%d)", tok_ret, n_images);
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_error(
            env, callback, callbacks, "Failed to tokenize multimodal prompt.", 0, 0
        );
    }

    llama_pos n_past = 0;
    int32_t n_ctx = llama_n_ctx(handle->ctx);
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_done(env, callback, callbacks, "cancelled", 0, 0);
    }
    // The 6th argument is the helper's BATCH size, not the context size: passing n_ctx made it
    // build batches larger than the context's n_batch, which llama_decode rejects.
    clear_text_cache(handle);
    int32_t eval_ret = mtmd_helper_eval_chunks(handle->mtmd_ctx, handle->ctx,
                                                chunks, n_past, 0,
                                                static_cast<int32_t>(llama_n_batch(handle->ctx)),
                                                true, &n_past);
    // Free bitmaps and chunks after evaluation
    for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
    mtmd_input_chunks_free(chunks);

    if (eval_ret != 0) {
        LOGE("mtmd_helper_eval_chunks failed with code %d", eval_ret);
        clear_text_cache(handle);
        return report_error(
            env, callback, callbacks, "Multimodal prefill failed.",
            static_cast<int32_t>(n_past), 0
        );
    }
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        clear_text_cache(handle);
        return report_done(
            env, callback, callbacks, "cancelled", static_cast<int32_t>(n_past), 0
        );
    }

    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Multimodal prefill: input_tokens=%lld, images=%d, duration_ms=%lld",
         (long long)n_past, n_images, (long long)prefill_ms);

    // --- Generation loop (same as text-only path) ---
    std::string sampler_error;
    common_sampler * smpl = init_chat_sampler(
        handle, metadata, temperature, top_p,
        frequency_penalty, presence_penalty, sampler_error
    );
    if (!smpl) {
        const char * message = sampler_error.empty()
            ? "Unable to initialize chat sampler"
            : sampler_error.c_str();
        clear_text_cache(handle);
        return report_error(
            env, callback, callbacks, message, static_cast<int32_t>(n_past), 0
        );
    }

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    NativeChatParser parser(metadata, *handle);
    const char * stop_reason = nullptr;
    std::string failure;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = common_sampler_sample(smpl, handle->ctx, -1);
        common_sampler_accept(smpl, new_token_id, true);

        if (llama_vocab_is_eog(handle->vocab, new_token_id) &&
            !is_preserved_token(metadata, new_token_id)) {
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(handle->ctx, single) != 0) {
            failure = "Decode failed";
            break;
        }
        generated++;
        parser.add_token(piece, new_token_id);

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!parser.update(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len, true, failure
                )) {
                if (failure.empty()) failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (failure.empty() && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!parser.update(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size(), true, failure
            )) {
            if (failure.empty()) failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (failure.empty() && !utf8_pending.empty()) {
        failure = "Generated incomplete UTF-8 output";
    }
    if (failure.empty() && !consumer_closed && stop_reason &&
        std::strcmp(stop_reason, "cancelled") != 0 &&
        !parser.finish(env, callback, callbacks, failure)) {
        if (failure.empty()) failure = "Stream consumer closed";
        consumer_closed = true;
    }
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Multimodal decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure.empty() ? stop_reason : "error");
    LOGD("Multimodal request: input_tokens=%lld, output_tokens=%d, total_ms=%lld",
         (long long)n_past, generated, (long long)request_ms);
    common_sampler_free(smpl);
    const int32_t input_tokens = static_cast<int32_t>(n_past);
    clear_text_cache(handle);
    if (!failure.empty()) {
        return report_error(
            env, callback, callbacks, failure.c_str(), input_tokens, generated
        );
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

// Exact token count of plain text under the loaded model's own vocabulary. Special tokens are
// neither added nor parsed: the caller measures content, while role and template markers are
// accounted for separately. Returns a negative value when the count is unavailable.
JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatCountTokens(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring text) {
    if (!handle_ptr) return -1;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->vocab) return -1;
    std::string input;
    if (!read_java_string(env, text, input)) return -1;
    if (input.empty()) return 0;
    // llama_tokenize returns the negated required length when the output buffer is too small, so a
    // zero-capacity call is the documented way to ask for the count alone.
    const int32_t needed = llama_tokenize(
        handle->vocab, input.c_str(), static_cast<int32_t>(input.size()),
        nullptr, 0, false, false
    );
    if (needed >= 0) return needed;
    // A count that cannot be negated is reported as unavailable rather than overflowed.
    if (needed == INT32_MIN) return -1;
    return -needed;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);

    if (handle->mtmd_ctx) mtmd_free(handle->mtmd_ctx);
    handle->chat_templates.reset();
    if (handle->ctx)   llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);

    LOGD("Chat model freed");
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatCancel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    handle->cancelled.store(true, std::memory_order_relaxed);
    LOGD("Cancellation requested");
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatSetPrefixCacheDir(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring directory, jlong max_bytes) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    std::string path;
    if (!read_java_string(env, directory, path)) return;
    handle->prefix_cache_dir = path;
    if (max_bytes > 0) handle->prefix_cache_max_bytes = static_cast<uint64_t>(max_bytes);
    // Apply a lowered budget right away, not only after the next save.
    agora::prefix_cache::evict(path, handle->prefix_cache_max_bytes);
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatSetMemoryCheckpointBudget(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr, jlong per_checkpoint, jlong total) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (per_checkpoint >= 0) handle->memory_checkpoint_max_bytes = static_cast<size_t>(per_checkpoint);
    if (total >= 0) handle->memory_total_max_bytes = static_cast<size_t>(total);
}

// Frees the RAM checkpoints; the disk snapshots stay. Called under memory pressure.
JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatDropMemoryCheckpoints(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    handle->memory_turns.clear();
    handle->memory_prefix = {};
}

} // extern "C"
