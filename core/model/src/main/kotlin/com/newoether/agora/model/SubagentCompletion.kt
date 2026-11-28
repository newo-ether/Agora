package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A durable outbound result. Run/message status remains authoritative for generation. */
@Serializable
data class SubagentCompletion(
    val id: String,
    val status: String,
    val text: String,
    val modelMessageId: String? = null,
    val errorText: String? = null,
    val errorCode: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(status in setOf("completed", "failed", "stopped"))
    }
    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun encode(value: SubagentCompletion): String = codec.encodeToString(serializer(), value)
        fun decode(raw: String?): SubagentCompletion? = raw?.let { codec.decodeFromString(serializer(), it) }
    }
}

/** Selected-parent capsule projection; only explicit agent_list loads optional diagnostics. */
@Serializable
data class SubagentSummary(
    val id: String,
    val title: String,
    val modelId: String?,
    val parentConversationId: String,
    val status: String,
    val errorText: String? = null,
    val errorCode: String? = null,
)
