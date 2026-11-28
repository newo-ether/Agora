package com.newoether.agora.ui.settings

import com.newoether.agora.data.LocalChatModelConfig

internal data class LocalChatModelDraft(
    val modelId: String = "",
    val alias: String = "",
    val modelPath: String = "",
    val projectorPath: String = "",
    val nCtx: String = "8192",
    // Gemma-family instruct models are post-trained for temp 1.0 / top_p 0.95; the sampler chain
    // keeps min_p 0.05 as tail cutoff in place of the card's top_k 64.
    val temperature: String = "1.0",
    val topP: String = "0.95",
    val maxTokens: String = "4096",
) {
    fun validate(isIdTaken: (String) -> Boolean): LocalChatModelDraftError? {
        val id = modelId.trim()
        return when {
            id.isBlank() -> LocalChatModelDraftError.ID_REQUIRED
            !Regex("^[a-z0-9._-]+$").matches(id) -> LocalChatModelDraftError.ID_INVALID
            isIdTaken(id) -> LocalChatModelDraftError.ID_TAKEN
            modelPath.isBlank() -> LocalChatModelDraftError.MODEL_REQUIRED
            nCtx.toIntOrNull()?.takeIf { it > 0 } == null -> LocalChatModelDraftError.CONTEXT
            temperature.toFloatOrNull()?.takeIf { it in 0f..2f } == null ->
                LocalChatModelDraftError.TEMPERATURE
            topP.toFloatOrNull()?.takeIf { it in 0f..1f } == null -> LocalChatModelDraftError.TOP_P
            maxTokens.toIntOrNull()?.takeIf { it > 0 } == null -> LocalChatModelDraftError.MAX_TOKENS
            maxTokens.toInt() > nCtx.toInt() -> LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT
            else -> null
        }
    }

    fun toConfig(initial: LocalChatModelConfig?): LocalChatModelConfig {
        val id = modelId.trim()
        return (initial ?: LocalChatModelConfig(modelId = id, alias = id)).copy(
            modelId = id,
            alias = alias.ifBlank { id },
            localFilePath = modelPath,
            mmprojPath = projectorPath,
            nCtx = nCtx.toInt(),
            temperature = temperature.toFloat(),
            topP = topP.toFloat(),
            maxTokens = maxTokens.toInt(),
        )
    }

    companion object {
        fun from(initial: LocalChatModelConfig?): LocalChatModelDraft = initial?.let {
            LocalChatModelDraft(
                modelId = it.modelId,
                alias = it.alias,
                modelPath = it.localFilePath,
                projectorPath = it.mmprojPath,
                nCtx = it.nCtx.toString(),
                temperature = it.temperature.toString(),
                topP = it.topP.toString(),
                maxTokens = it.maxTokens.toString(),
            )
        } ?: LocalChatModelDraft()
    }
}

internal enum class LocalChatModelDraftError {
    ID_REQUIRED,
    ID_INVALID,
    ID_TAKEN,
    MODEL_REQUIRED,
    CONTEXT,
    TEMPERATURE,
    TOP_P,
    MAX_TOKENS,
    OUTPUT_EXCEEDS_CONTEXT,
}
