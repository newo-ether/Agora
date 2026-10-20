package com.newoether.agora.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class RemoteSession(
    val id: String, val title: String, val cwd: String, val updatedAt: Long,
    val status: String? = null,
    @kotlinx.serialization.Transient val listCursor: String? = null,
    @kotlinx.serialization.Transient val acceptedTurnId: String? = null,
)
@Serializable
internal data class RemoteSessionStatus(
    val id: String, val status: String? = null, val activeTurnId: String? = null,
    val completedTurnId: String? = null, val hasUnreadTurn: Boolean = false,
)
@Serializable
internal data class RemoteMessage(
    val id: String, val turnId: String, val clientId: String?, val role: String,
    val text: String, val timestamp: Long,
    val activity: RemoteActivity? = null,
    val groupId: String? = null,
    val nativeId: String? = null, val textOffset: Int = 0, val textContinues: Boolean = false,
    @kotlinx.serialization.Transient
    val streamingTextDeltas: List<com.newoether.agora.model.StreamingTextDelta> = emptyList(),
    val imageLinks: List<String> = emptyList(),
    @kotlinx.serialization.Transient
    val inlineImages: Map<String, com.newoether.agora.model.MarkdownImage> = emptyMap(),
    val error: Boolean = false,
)
@Serializable
internal data class RemoteActivity(
    val type: String, val toolName: String? = null, val arguments: String? = null,
    val result: String? = null, val state: String? = null, val durationMs: Long? = null,
    val imagePath: String? = null,
    @kotlinx.serialization.Transient
    val images: List<com.newoether.agora.model.ToolImageAttachment> = emptyList(),
    val structuredResult: kotlinx.serialization.json.JsonObject? = null,
)
@Serializable
internal data class RemoteQueuedMessage(val id: String, val clientId: String, val text: String)
@Serializable
internal data class RemoteSessionPage(
    val sessions: List<RemoteSession>, val nextCursor: String?,
    val statuses: List<RemoteSessionStatus> = emptyList(),
)
@Serializable
internal data class RemoteConversationPage(
    val messages: List<RemoteMessage>, val nextCursor: String?, val queued: List<RemoteQueuedMessage>,
    val runtime: RemoteRuntime? = null, val pageCursor: String? = null,
    val continuationCursor: String? = null,
    val nodes: List<RemoteMessageNode> = emptyList(),
    val inputReceipts: List<RemoteInputReceipt> = emptyList(),
)
/** Input confirmed by the native owner, independent of bounded presentation pages. */
@Serializable
internal data class RemoteInputReceipt(val clientId: String, val turnId: String, val messageId: String)
@Serializable
internal data class RemoteRuntime(
    val status: String, val activeTurnId: String? = null, val model: String? = null,
    val contextTokens: Int? = null, val contextWindow: Int? = null,
    val completedTurnId: String? = null,
    val effort: String? = null, val serviceTier: String? = null,
    val serviceTierKnown: Boolean = false, val activeTurnHasUserMessage: Boolean = false,
    val skills: List<RemoteSkill> = emptyList(),
    val agent: String? = null,
) { val isRunning: Boolean get() = status == "active" }
@Serializable
internal data class RemoteModel(
    val id: String, val name: String, val isDefault: Boolean = false,
    val reasoningEfforts: List<String>? = null, val defaultReasoningEffort: String? = null,
    val serviceTiers: List<RemoteServiceTier>? = null, val defaultServiceTier: String? = null,
)
@Serializable
internal data class RemoteServiceTier(val id: String, val name: String, val description: String = "")
internal data class RemoteSettings(
    val model: String? = null, val effort: String? = null,
    val serviceTier: String? = null, val updateServiceTier: Boolean = false,
) {
    fun merge(patch: RemoteSettings) = RemoteSettings(patch.model ?: model, patch.effort ?: effort,
        if (patch.updateServiceTier) patch.serviceTier else serviceTier, updateServiceTier || patch.updateServiceTier)
    fun body(): String = buildJsonObject {
        model?.let { put("model", it) }; effort?.let { put("effort", it) }
        if (updateServiceTier) put("serviceTier", serviceTier?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    }.toString()
}
