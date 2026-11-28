#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include "jni_utf8.h"

using agora::jni::read_java_path;
using agora::jni::read_java_string;

#define LOG_TAG "LlamaEngine"
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#define LOGE(...) ((void)0)
#endif

struct LlamaHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_embd        = 0;
    bool is_encoder       = false;
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaEngine_nativeInitializeBackends(
    JNIEnv * env, jclass /*clazz*/, jstring native_library_dir) {

    if (!native_library_dir) return JNI_FALSE;
    std::string directory;
    if (!read_java_path(env, native_library_dir, directory)) return JNI_FALSE;

    ggml_backend_load_all_from_path(directory.c_str());

    if (!ggml_backend_reg_by_name("CPU")) {
        LOGE("No compatible CPU backend was loaded");
        return JNI_FALSE;
    }
    llama_backend_init();
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_LlamaEngine_nativeLoadModel(
    JNIEnv * env, jclass /*clazz*/, jstring path) {

    std::string path_str;
    if (!read_java_path(env, path, path_str)) return 0;

    LlamaHandle * handle = new LlamaHandle();
    if (!handle) {
        return 0;
    }

    // Load model
    llama_model_params model_params = llama_model_default_params();
    handle->model = llama_model_load_from_file(path_str.c_str(), model_params);

    if (!handle->model) {
        delete handle;
        return 0;
    }

    handle->vocab = llama_model_get_vocab(handle->model);
    handle->n_embd = llama_model_n_embd_out(handle->model);

    // Create context with mean pooling for embeddings
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.embeddings   = true;
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    ctx_params.n_ctx = 512;   // enough for embedding input
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.no_perf = false;

    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (!handle->ctx) {
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }

    handle->is_encoder = llama_model_has_encoder(handle->model);
    LOGD("Model loaded: n_embd=%d, is_encoder=%d, n_ctx_train=%d",
         handle->n_embd, handle->is_encoder, llama_model_n_ctx_train(handle->model));

    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaEngine_nativeFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    LlamaHandle * handle = reinterpret_cast<LlamaHandle *>(handle_ptr);

    if (handle->ctx)   llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);

    delete handle;
}

JNIEXPORT jfloatArray JNICALL
Java_com_newoether_agora_api_LlamaEngine_nativeComputeEmbedding(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring text) {

    if (!handle_ptr) return nullptr;
    LlamaHandle * handle = reinterpret_cast<LlamaHandle *>(handle_ptr);
    if (!handle->ctx || !handle->model) return nullptr;

    std::string input;
    if (!read_java_string(env, text, input)) return nullptr;

    if (input.empty()) return nullptr;

    // Tokenize
    const int32_t n_tokens_max = input.length() + 32;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(handle->vocab, input.c_str(),
                                      input.size(), tokens.data(),
                                      n_tokens_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(handle->vocab, input.c_str(),
                                  input.size(), tokens.data(),
                                  -n_tokens, true, true);
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization returned 0 tokens for text len=%zu", input.size());
        return nullptr;
    }
    tokens.resize(n_tokens);
    LOGD("Tokenized: %d tokens for text len=%zu", n_tokens, input.size());

    // Truncate to context size
    if (n_tokens > 512) {
        n_tokens = 512;
        tokens.resize(512);
    }

    // Every input is an independent embedding sequence, even when the native model/context stays
    // resident across a batch or later request. Positions restart at zero below, so stale sequence
    // memory must be cleared before encode/decode.
    llama_memory_clear(llama_get_memory(handle->ctx), true);

    // Create batch
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0]= 0;
        batch.logits[i]   = (i == n_tokens - 1) ? 1 : 0;
    }
    batch.n_tokens = n_tokens;

    // Run inference
    int32_t ret;
    if (handle->is_encoder) {
        ret = llama_encode(handle->ctx, batch);
    } else {
        ret = llama_decode(handle->ctx, batch);
    }
    llama_batch_free(batch);

    if (ret != 0) {
        LOGE("llama_encode/decode returned error code %d", ret);
        return nullptr;
    }

    // Get pooled embedding (mean pooling is done by llama.cpp when pooling_type is MEAN)
    const float * embd = llama_get_embeddings_seq(handle->ctx, 0);
    if (!embd) {
        LOGE("llama_get_embeddings_seq returned null");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(handle->n_embd);
    env->SetFloatArrayRegion(result, 0, handle->n_embd, embd);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaEngine_nativeGetEmbeddingDim(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return 0;
    LlamaHandle * handle = reinterpret_cast<LlamaHandle *>(handle_ptr);
    return handle->n_embd;
}

} // extern "C"
