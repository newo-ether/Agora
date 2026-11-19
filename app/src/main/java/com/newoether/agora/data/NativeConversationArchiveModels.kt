package com.newoether.agora.data

import com.newoether.agora.automation.LoopPolicy
import kotlinx.serialization.Serializable

/** Archive headers must describe a one-level forest before any durable graph is replaced. */
internal suspend fun validateSubagentArchiveOwners(
    conversations: List<com.newoether.agora.data.local.ChatEntity>,
    availableIds: Set<String>,
    readExisting: suspend (String) -> com.newoether.agora.data.local.ChatEntity?,
) {
    val imported = conversations.associateBy { it.id }
    conversations.forEach { child ->
        child.parentConversationId?.let { parentId ->
            require(parentId != child.id && parentId in availableIds) { "Invalid subagent parent" }
            val parent = imported[parentId] ?: readExisting(parentId)
            require(parent != null && parent.parentConversationId == null) { "Nested subagent parent" }
        }
        val completion = com.newoether.agora.model.SubagentCompletion.decode(child.subagentResultJson)
        require((completion == null) == (child.subagentResultId == null)) { "Incomplete subagent result" }
        require(completion?.id == child.subagentResultId) { "Mismatched subagent result" }
        // Validation may repair display status without changing a durable completion or receipt.
        require(child.subagentResultStatus in setOf("completed", "failed", "stopped") ||
            completion == null && child.subagentResultStatus == null) {
            "Invalid subagent status"
        }
    }
}

@Serializable
internal data class NativeConversationPayload(
    val conversations: List<NativeExportChatEntity>,
    val runs: List<NativeExportRunEntity>,
    val messages: List<NativeExportMessageEntity>,
    val loops: List<NativeExportLoopEntity>,
)

@Serializable
internal data class NativeTasksPayload(val tasks: List<NativeExportTaskEntity>)

@Serializable
internal data class NativeExportChatEntity(
    val id: String,
    val title: String,
    val lastUpdated: Long,
    val createdAt: Long? = null,
    val dataChangedAt: Long = 0L,
    val isPinned: Boolean = false,
    val parentConversationId: String? = null,
    val subagentResultJson: String? = null,
    val subagentResultId: String? = null,
    val subagentResultStatus: String? = null,
    val subagentDeliveredResultId: String? = null,
    val todoJson: String? = null,
    val selectedBranchesJson: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false,
    val selectedRunBranchesJson: String? = null,
    val draftText: String = "",
    val draftAttachments: String? = null,
    val conversationSettings: ConversationSettings? = null,
    val hasUnreadGeneration: Boolean = false,
)

@Serializable
internal data class NativeExportRunEntity(
    val id: String,
    val conversationId: String,
    val parentRunId: String? = null,
    val status: String = "COMPLETED",
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val stopRequestedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val currentPass: Int = 0,
    val legacyAmbiguous: Boolean = false,
)

@Serializable
internal data class NativeExportTaskEntity(
    val id: String,
    val name: String,
    val prompt: String,
    val systemPrompt: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    val cronExpr: String,
    val runAt: Long? = null,
    val nextRunAt: Long = 0L,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
)

@Serializable
internal data class NativeExportLoopEntity(
    val conversationId: String,
    val intervalMs: Long,
    val prompt: String? = null,
    val nextFireAt: Long = 0L,
    val cycleCount: Int = 0,
    val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
    val active: Boolean = true,
    val revision: Long = 0L,
)

@Serializable
internal data class NativeExportMessageEntity(
    val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val inputTokenCount: Int? = null,
    val cachedInputTokenCount: Int? = null,
    val cacheWriteInputTokenCount: Int? = null,
    val uncachedInputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val reasoningTokenCount: Int? = null,
    val generationDurationMs: Long? = null,
    val timeToFirstTokenMs: Long? = null,
    val totalGenerationMs: Long? = null,
    val status: String = "SUCCESS",
    val participant: String = "MODEL",
    val timestamp: Long,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCallJson: String? = null,
    val attachmentMeta: String? = null,
    val runId: String? = null,
    val runSequence: Long? = null,
    val consumedAtPass: Int? = null,
    /** Added in backup v6; absent in older archives. */
    val sourceJson: String? = null,
)
