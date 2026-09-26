package com.newoether.agora.data

import com.newoether.agora.automation.LoopPolicy
import kotlinx.serialization.Serializable

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
    val dataChangedAt: Long = 0L,
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
)
