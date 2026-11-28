package com.newoether.agora.api.local

import android.content.Context
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.OnnxLlmEngine
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.OnnxChatModelBootstrap
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * On-device LLM chat provider backed by onnxruntime-genai (the ONNX replacement for the GGUF
 * LocalProvider). Streams a single on-device Qwen3 model. Text-only; the model's built-in chat
 * template is applied via genai.
 */
class OnnxLlmProvider(
    private val context: Context,
) : LlmProvider {

    override val name: String = Constants.PROVIDER_LOCAL
    override val defaultBaseUrl: String = ""
    override val nativeTextParsingAuthoritative: Boolean = true

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = generateOnnx(messages, config)

    private fun generateOnnx(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): Flow<StreamEvent> = flow {
        if (!OnnxChatModelBootstrap.isInstalled(context)) {
            emit(StreamEvent.Error(GenerationError.LocalModel("On-device chat model not installed")))
            return@flow
        }
        val engine = engine(context)
        if (engine == null || !engine.isAvailable()) {
            emit(StreamEvent.Error(GenerationError.LocalModel("Failed to load the on-device chat model")))
            return@flow
        }

        val prompt = buildChatMlPrompt(messages, config.systemPrompt)

        val maxTokens = (config.maxTokens ?: DEFAULT_MAX_TOKENS).coerceIn(1, MAX_TOKENS_CAP)
        val temperature = config.temperature ?: DEFAULT_TEMPERATURE
        val topP = config.topP ?: DEFAULT_TOP_P

        val deltas = ArrayDeque<String>()
        val result = engine.generate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            shouldStop = { !engineActive },
        ) { delta -> deltas.addLast(delta) }

        // genai's generate() is blocking; emit the accumulated deltas as chunks. (A finer-grained
        // channel could stream mid-generation; kept simple + correct for the first cut.)
        while (deltas.isNotEmpty()) {
            if (!coroutineContext.isActive) break
            emit(StreamEvent.TextChunk(deltas.removeFirst()))
        }
        if (result == null) {
            emit(StreamEvent.Error(GenerationError.LocalModel("On-device generation failed")))
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        if (OnnxChatModelBootstrap.isInstalled(context)) listOf(OnnxChatModelBootstrap.MODEL_ID) else emptyList()

    private fun buildChatMlPrompt(messages: List<ChatMessage>, systemPrompt: String?): String {
        // Qwen3 uses ChatML. The genai model has no embedded chat_template, so build it directly.
        val sb = StringBuilder()
        if (!systemPrompt.isNullOrBlank()) {
            sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        }
        for (m in messages) {
            val role = when (m.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> continue
            }
            sb.append("<|im_start|>").append(role).append("\n").append(m.text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        // Qwen3 is a reasoning model; for plain chat, pre-fill an empty think block so it skips
        // (often looping) chain-of-thought and answers directly.
        sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    companion object {
        private const val TAG = "OnnxLlmProvider"
        private const val DEFAULT_MAX_TOKENS = 1024
        private const val MAX_TOKENS_CAP = 4096
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.9f

        @Volatile private var cached: OnnxLlmEngine? = null
        @Volatile private var engineActive: Boolean = true

        @Synchronized
        private fun engine(ctx: Context): OnnxLlmEngine? {
            if (!OnnxChatModelBootstrap.isInstalled(ctx)) return null
            val existing = cached
            if (existing != null && existing.isAvailable()) return existing
            engineActive = true
            val created = OnnxLlmEngine(OnnxChatModelBootstrap.modelDir(ctx))
            cached = created
            return created
        }

        /** Drop the cached engine (after removing/replacing the model). */
        @Synchronized
        fun reset() {
            try { cached?.close() } catch (_: Throwable) {}
            cached = null
        }
    }
}
