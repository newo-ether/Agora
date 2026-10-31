package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class ToolCallData(
    val toolName: String,
    val arguments: String,
    val result: String,
    val signature: String? = null,
    val toolCallId: String? = null,
    val resultImages: List<ToolImageAttachment> = emptyList(),
    /** Stable provider-supplied title. Internal protocol names remain in [toolName]. */
    val displayName: String? = null,
    /** Human-readable result content kept separate from the protocol-facing [result]. */
    val resultText: String? = null,
    /** Provider-declared structured result JSON, never inferred from arbitrary text. */
    val structuredResult: String? = null,
    /** Raw provider protocol items needed to reconstruct a stateless tool continuation. */
    val responseOutputItems: List<JsonObject> = emptyList(),
    /** Provider identity that owns [responseOutputItems]; foreign transports must ignore them. */
    val responseOutputItemProvider: String? = null,
    /** Transcription description of a tool-result image; travels with the result row. */
    val transcription: String? = null,
    /** Stable execution target retained for history without consulting a live registry. */
    val target: String? = null,
)

/** Tool images use the same measured artifact as ordinary attachments. */
typealias ToolImageAttachment = ImageArtifact

/** Disposable image presentation; an absent attachment is pending unless the read failed. */
@Immutable
data class MarkdownImage(
    val attachment: ToolImageAttachment? = null,
    val failed: Boolean = false,
)

@Immutable
data class StreamingTextDelta(
    val sequence: Long,
    val codePointCount: Int,
)

@Serializable
data class MessageSegment(
    val type: String, // "answer", "thought", "tool", "citation", "transcription", or terminal "error"
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val signature: String? = null,
    /**
     * Provider that issued [signature]. Signatures are opaque provider protocol state and must
     * never be replayed to a different wire protocol. This lives in the existing JSON segment
     * payload, so old rows remain readable without a Room schema migration.
     */
    val signatureProvider: String? = null,
    val durationMs: Long? = null,
    /** Durable UI lifecycle for tool segments. Null keeps old rows backward-compatible. */
    val toolState: String? = null,
    /** Bounded, display-only live output. The final model-facing result remains [toolResult]. */
    val toolProgress: String? = null,
    /** Resolved execution target for tool UI. Kept separate from lifecycle and output text. */
    val toolTarget: String? = null,
    /** Stable provider-supplied title. Internal protocol names remain in [toolName]. */
    val toolDisplayName: String? = null,
    /** Human-readable result content kept separate from the protocol-facing [toolResult]. */
    val toolResultText: String? = null,
    /** Provider-declared structured result JSON, never inferred from arbitrary text. */
    val toolStructuredResult: String? = null,
    /** Private-file metadata for image content returned by a tool. */
    val toolImages: List<ToolImageAttachment> = emptyList(),
    /** Opaque revision for a demand-loaded preview; never persisted or sent to a Provider. */
    @Transient
    val toolImageRequestKey: String? = null,
    /**
     * Transcription description of a tool-result image (file_view_image). Persisted WITH the result
     * row so the API projection can inject it into the model context — the round-boundary path
     * rebuild does not include the model message, so a segment living there is unreachable.
     * Displayed only in the Image Transcription thinking block, never in the tool card.
     */
    val toolTranscription: String? = null,
    /** Committed protocol row owning this tool body; root checkpoints retain only its call reference. */
    val toolRoundId: String? = null,
    /** Raw provider protocol items needed to reconstruct a stateless tool continuation. */
    val responseOutputItems: List<JsonObject> = emptyList(),
    /** Provider identity that owns [responseOutputItems]; foreign transports must ignore them. */
    val responseOutputItemProvider: String? = null,
    /** Stable semantic cause for terminal error segments. Null keeps old rows backward-compatible. */
    val errorCode: String? = null,
    /** Remote-only rendering hint; native tool payloads remain unchanged and never enter Room. */
    @Transient
    val remoteToolSource: String? = null,
    /** In-memory Provider delta boundaries for the active answer; never persisted to Room JSON. */
    @Transient
    val streamingTextDeltas: List<StreamingTextDelta> = emptyList(),
) {
    /** Provider-native segments keep their own replay format, not client function results. */
    val isProviderHostedTool: Boolean
        get() = type == "tool" && when (toolName) {
            "openai_search", "google_search", "code_execution" -> true
            else -> false
        }
}

object ToolExecutionStates {
    const val CALLING = "calling"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val EMPTY = "empty"
    const val FAILED = "failed"
    const val STOPPED = "stopped"
    const val BACKGROUND_RUNNING = "background_running"

    val TERMINAL = setOf(SUCCEEDED, EMPTY, FAILED, STOPPED, BACKGROUND_RUNNING)
}

object ThinkingSegmentDisplayModes {
    const val CARD = "card"
    const val BOTTOM_SHEET = "bottom_sheet"
    const val DEFAULT = CARD

    fun normalize(value: String?): String = when (value) {
        BOTTOM_SHEET -> BOTTOM_SHEET
        else -> CARD
    }

    fun isAvailableFor(toolCallDisplayMode: String?): Boolean =
        ToolCallDisplayModes.normalize(toolCallDisplayMode) != ToolCallDisplayModes.TIMELINE

    fun effectiveMode(
        thinkingSegmentDisplayMode: String?,
        toolCallDisplayMode: String?,
    ): String = if (isAvailableFor(toolCallDisplayMode)) {
        normalize(thinkingSegmentDisplayMode)
    } else {
        CARD
    }

    fun allowsAutoExpand(
        thinkingSegmentDisplayMode: String?,
        toolCallDisplayMode: String?,
    ): Boolean =
        ToolCallDisplayModes.normalize(toolCallDisplayMode) ==
            ToolCallDisplayModes.GROUPED_TIMELINE &&
            normalize(thinkingSegmentDisplayMode) == CARD
}

object ToolCallDisplayModes {
    const val TIMELINE = "timeline"
    const val GROUPED_TIMELINE = "grouped_timeline"
    const val DEFAULT = GROUPED_TIMELINE

    fun normalize(value: String?): String = when (value) {
        "compact" -> GROUPED_TIMELINE
        GROUPED_TIMELINE -> GROUPED_TIMELINE
        TIMELINE -> TIMELINE
        else -> DEFAULT
    }
}

enum class Participant {
    USER, MODEL, ERROR
}

enum class MessageStatus {
    TRANSCRIBING, SENDING, THINKING, TOOL_CALLING, SUCCESS, STOPPED, ERROR
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val tokenUsage: TokenUsage? = null,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default to SUCCESS for old messages
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCall: ToolCallData? = null,
    val segments: List<MessageSegment>? = null,
    val attachmentMeta: AttachmentMeta? = null,
    val retryText: String? = null,
    val runId: String? = null,
    val runSequence: Long? = null,
    val consumedAtPass: Int? = null,
    /** Set only on automatic user input (Task, Loop, non-blocking ask_user answers). */
    val source: MessageSource? = null,
    /** Optional in-memory page boundary; older pages cannot reparent a rendered list item. */
    val displayPageId: String? = null,
    /** Authenticated private files for inline Markdown images; never part of native history. */
    val markdownImages: Map<String, MarkdownImage> = emptyMap(),
    /** Disposable off-main Markdown preparation; never persisted in Room or native history. */
    val preparedMarkdown: Map<String, com.mikepenz.markdown.model.State.Success> = emptyMap(),
    val preparedMarkdownBytes: Long = 0,
    /** Facts keyed by the exact file paths in [images], including API-only tool image rows. */
    val imageArtifacts: Map<String, ImageArtifact> = emptyMap(),

)

@Immutable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    /** Set when this conversation is a task execution; drives the "from task" banner. */
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false,
    val hasUnreadGeneration: Boolean = false,
    val isPinned: Boolean = false,
    val selectedBranchesJson: String? = null,
    val parentConversationId: String? = null,
)

fun ChatMessage.isContextCompact(): Boolean =
    id.startsWith(com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX)

fun ChatMessage.isSuccessfulContextCompact(): Boolean =
    isContextCompact() && status == MessageStatus.SUCCESS

@Immutable
data class StableMessageList(val list: List<ChatMessage> = emptyList())

@Immutable
data class StableModelAliases(
    val map: Map<String, String> = emptyMap(),
    val providerNames: Map<String, Boolean> = emptyMap(),
)
