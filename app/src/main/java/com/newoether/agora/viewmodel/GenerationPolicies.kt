package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants

/** Never route a request through an arbitrary fallback provider. */
internal fun <T> requireRegisteredProvider(providers: Map<String, T>, name: String): T =
    requireNotNull(providers[name]) { "Provider is not registered: $name" }

internal data class GenerationTerminalDisposition(
    val runStatus: RunStatus,
    val endReason: RunEndReason,
    val markConversationUnread: Boolean,
)

internal fun shouldPostGenerationTerminalNotification(
    messageStatus: MessageStatus,
    hasPendingGuidance: Boolean,
    isContextCompact: Boolean,
    appInForeground: Boolean,
    conversationVisible: Boolean?,
): Boolean {
    if (conversationVisible == null) {
        return !appInForeground &&
            messageStatus == MessageStatus.SUCCESS &&
            !hasPendingGuidance &&
            !isContextCompact
    }
    val terminalNeedsAttention =
        messageStatus == MessageStatus.ERROR ||
            (messageStatus == MessageStatus.SUCCESS && !hasPendingGuidance)
    return !conversationVisible &&
        terminalNeedsAttention &&
        (!isContextCompact || messageStatus == MessageStatus.ERROR)
}

/**
 * Every provider-generation exit closes its durable Run. Pending guidance only defers the
 * conversation-unread/completion presentation; it cannot keep the origin Run live because the
 * normal Send boundary that consumes that guidance must create a distinct Run.
 */
internal fun generationTerminalDisposition(
    messageStatus: MessageStatus,
    hasPendingGuidance: Boolean,
    conversationVisible: Boolean? = null,
): GenerationTerminalDisposition = when (messageStatus) {
    MessageStatus.STOPPED -> GenerationTerminalDisposition(
        RunStatus.STOPPED,
        RunEndReason.USER_STOPPED,
        markConversationUnread = false,
    )
    MessageStatus.ERROR -> GenerationTerminalDisposition(
        RunStatus.FAILED,
        RunEndReason.PROVIDER_ERROR,
        markConversationUnread = conversationVisible == false,
    )
    else -> GenerationTerminalDisposition(
        RunStatus.COMPLETED,
        if (hasPendingGuidance) RunEndReason.CONTINUATION_PENDING else RunEndReason.MODEL_COMPLETED,
        markConversationUnread = !hasPendingGuidance && conversationVisible != true,
    )
}

/**
 * Throttles durable stream snapshots while allowing lifecycle boundaries to force a write.
 * The first snapshot is always accepted, including when the clock moves backwards.
 */
internal class StreamingCheckpointGate(
    private val intervalMs: Long = 1_000L,
) {
    private var lastCheckpointAt: Long? = null

    init {
        require(intervalMs > 0)
    }

    fun shouldCheckpoint(nowMs: Long, force: Boolean = false): Boolean {
        val previous = lastCheckpointAt
        if (!force && previous != null && nowMs >= previous && nowMs - previous < intervalMs) {
            return false
        }
        lastCheckpointAt = nowMs
        return true
    }
}

/**
 * Shared visible-snapshot cadence for every ordinary generation surface, including Compact.
 *
 * Callers record only completed publications. A clock rollback is treated as immediately due so
 * stream output can never become stuck behind a stale wall-clock timestamp.
 */
internal class StreamingUiUpdateGate(
    private val intervalMs: Long = 50L,
) {
    private var lastPublishedAt: Long? = null

    init {
        require(intervalMs > 0)
    }

    fun isDue(nowMs: Long): Boolean {
        val previous = lastPublishedAt ?: return true
        return nowMs < previous || nowMs - previous >= intervalMs
    }

    fun recordPublished(nowMs: Long) {
        lastPublishedAt = nowMs
    }

    fun reset() {
        lastPublishedAt = null
    }
}

/**
 * Returns only reasoning produced since the previous tool-round boundary.
 *
 * A model message keeps the full segment timeline for display, while each synthetic tool row must
 * contain only the protocol blocks that belong to that one round. Reusing every historical thought
 * here makes signatures and reasoning grow quadratically across a long agent run.
 */
internal fun toolRoundThoughtSegments(
    segments: List<MessageSegment>,
    fromIndex: Int,
): List<MessageSegment> {
    val safeStart = fromIndex.coerceIn(0, segments.size)
    return segments.subList(safeStart, segments.size).filter { it.type == "thought" }
}

/**
 * Removes only the strict cumulative thought prefix written by older Agora builds.
 *
 * Legacy tool rows for one run were shaped as `[thought 1, ..., thought N, tool N]`; replaying all
 * rows therefore sent the same signed reasoning over and over. Current rows contain only their own
 * round. This tracker accepts both layouts and strips a prefix only when a later row is a strict
 * extension of the exact thought history already observed. Equal or unrelated content is retained,
 * so this never guesses from text and cannot classify an ordinary answer as protocol data.
 */
internal class ToolRoundHistoryCompactor {
    private val thoughtHistoryByRun = mutableMapOf<String, List<MessageSegment>>()

    fun compact(runId: String, segments: List<MessageSegment>): List<MessageSegment> {
        val thoughts = segments.filter { it.type == "thought" }
        if (thoughts.isEmpty()) return segments

        val history = thoughtHistoryByRun[runId].orEmpty()
        val repeatedPrefixSize = history.size.takeIf { prefixSize ->
            prefixSize > 0 &&
                thoughts.size > prefixSize &&
                thoughts.subList(0, prefixSize) == history
        } ?: 0

        thoughtHistoryByRun[runId] = when {
            repeatedPrefixSize > 0 -> thoughts
            history.isEmpty() -> thoughts
            thoughts == history -> history
            else -> history + thoughts
        }
        if (repeatedPrefixSize == 0) return segments

        var thoughtsToDrop = repeatedPrefixSize
        return segments.filter { segment ->
            if (segment.type == "thought" && thoughtsToDrop > 0) {
                thoughtsToDrop--
                false
            } else {
                true
            }
        }
    }
}

internal fun applyMessageTemplatesToMessages(
    messages: List<ChatMessage>,
    userPrepend: String?,
    userPostpend: String?,
    assistantPrepend: String? = null,
    assistantPostpend: String? = null,
): List<ChatMessage> {
    if (
        userPrepend == null && userPostpend == null &&
        assistantPrepend == null && assistantPostpend == null
    ) return messages
    val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val sentDateSdf = java.text.SimpleDateFormat(
        PredefinedVariables.SENT_DATE_PATTERN,
        java.util.Locale.US,
    )
    return messages.map { message ->
        val isSpecialMessage =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX) ||
                message.id.startsWith(Constants.COMPACT_MSG_PREFIX) ||
                message.id.startsWith("context_summary_") ||
                message.id.startsWith("api_initial_user_") ||
                message.id.startsWith("api_compact_continuation_")
        val template = when {
            isSpecialMessage || message.text.isEmpty() -> null
            message.participant == Participant.USER -> userPrepend to userPostpend
            message.participant == Participant.MODEL -> assistantPrepend to assistantPostpend
            else -> null
        } ?: return@map message
        val timestamp = java.util.Date(message.timestamp)
        val messageModelId = message.modelName
            ?.takeIf(String::isNotBlank)
            ?.let { ModelId.parse(it).modelName }
            .orEmpty()
        fun String?.resolveMessageVariables(): String = this
            ?.replace("{${PredefinedVariables.SENT_TIME}}", timeSdf.format(timestamp))
            ?.replace("{${PredefinedVariables.SENT_DATE}}", sentDateSdf.format(timestamp))
            ?.replace("{${PredefinedVariables.MESSAGE_MODEL_ID}}", messageModelId)
            .orEmpty()
        val before = template.first.resolveMessageVariables()
        val after = template.second.resolveMessageVariables()
        if (before.isEmpty() && after.isEmpty()) message
        else message.copy(text = before + message.text + after)
    }
}

internal fun applyUserTemplateToMessages(
    messages: List<ChatMessage>,
    prepend: String?,
    postpend: String?
): List<ChatMessage> = applyMessageTemplatesToMessages(
    messages = messages,
    userPrepend = prepend,
    userPostpend = postpend,
)

/**
 * Exact API-only history projection shared by dispatch, Context accounting, and Auto Compact.
 *
 * Provider adapters still own canonical role/tool validation and hard-cap rollout. This step owns
 * the transformations that happen immediately before that shared provider boundary, so admission
 * policy and the bottom-bar estimate cannot omit text or images that dispatch will actually send.
 */
internal fun projectGenerationInputMessages(
    messages: List<ChatMessage>,
    includeImages: Boolean,
    userPrepend: String?,
    userPostpend: String?,
    assistantPrepend: String? = null,
    assistantPostpend: String? = null,
    initialUserPrompt: String? = null,
    phoneMaxScreenshots: Int = com.newoether.agora.phone.PHONE_MAX_SCREENSHOTS_DEFAULT,
): List<ChatMessage> {
    val projected = applyMessageTemplatesToMessages(
        messages = projectToolResultImagesToUserMessage(
            messages = projectAssistantImagesToLatestUserMessage(
                projectAutomaticMessagesForApi(
                    projectPhoneToolImages(messages, phoneMaxScreenshots).map(::projectSandboxGeneratedImages),
                ),
                includeImages,
            ),
            includeImages = includeImages,
        ),
        userPrepend = userPrepend,
        userPostpend = userPostpend,
        assistantPrepend = assistantPrepend,
        assistantPostpend = assistantPostpend,
    ).let { apiMessages ->
        if (includeImages) {
            apiMessages
        } else {
            apiMessages.map { message ->
                if (message.images.isEmpty()) message else message.copy(images = emptyList())
            }
        }
    }
    val prompt = initialUserPrompt?.takeIf(String::isNotBlank) ?: return projected
    val parent = projected.lastOrNull()
    return projected + ChatMessage(
        id = "api_initial_user_${parent?.id.orEmpty()}",
        parentId = parent?.id,
        text = prompt,
        participant = Participant.USER,
        timestamp = parent?.timestamp?.let { if (it == Long.MAX_VALUE) it else it + 1L } ?: 0L,
    )
}
