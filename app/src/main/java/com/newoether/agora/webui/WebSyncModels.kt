package com.newoether.agora.webui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class WebSyncCommand(
    val type: String,
    val conversationId: String? = null,
    val messageIds: List<String> = emptyList(),
    /** The browser's open-request sequence, echoed in [WebSyncEvent.Opened]. */
    val seq: Long = 0L,
    val text: String? = null,
    val revision: Long = 0L,
    val actionId: Long = 0L,
    val modelId: String? = null,
    val queuedId: String? = null,
    val attachmentId: String? = null,
    val pages: List<Int> = emptyList(),
    val frameCount: Int? = null,
    val intervalMs: Long? = null,
    val setting: String? = null,
    val enabled: Boolean? = null,
    val value: String? = null,
    val tokens: Int? = null,
    val parameters: com.newoether.agora.data.ConversationSettings? = null,
    val retainCount: Int? = null,
)

@Serializable
internal sealed interface WebSyncEvent {
    @Serializable @SerialName("connection")
    data class Connection(val connectionId: String) : WebSyncEvent
    @Serializable @SerialName("conversations")
    data class Conversations(val items: List<WebConversation>) : WebSyncEvent

    /** The selected branch of the open conversation, without message bodies. */
    @Serializable @SerialName("path")
    data class Path(
        val conversationId: String,
        val messages: List<WebPathEntry>,
        val generating: Boolean,
    ) : WebSyncEvent

    /** The body of one watched row. */
    @Serializable @SerialName("payload")
    data class Payload(val conversationId: String, val message: WebMessage) : WebSyncEvent

    /** The in-flight message; it replaces the durable body of the row with the same id. */
    @Serializable @SerialName("streaming")
    data class Streaming(val conversationId: String, val message: WebMessage?) : WebSyncEvent

    @Serializable @SerialName("deleted")
    data class Deleted(val conversationId: String) : WebSyncEvent

    /** The phone's display settings and live-timer strings; sent first and on each change. */
    @Serializable @SerialName("display")
    data class Display(
        val toolCallDisplayMode: String,
        val thinkingSegmentDisplayMode: String,
        val autoExpandActiveGroup: Boolean,
        val autoWrapCodeBlocks: Boolean,
        val blurEffectsEnabled: Boolean,
        val reduceMotion: Boolean,
        val liveThinking: WebLiveTimerStrings,
    ) : WebSyncEvent

    @Serializable @SerialName("load_failed")
    data class LoadFailed(val conversationId: String) : WebSyncEvent

    /**
     * The runtime moved this connection to [conversationId] (null is New Chat), for example after
     * a New Chat send. [seq] is the browser open request it supersedes; a newer request wins.
     */
    @Serializable @SerialName("opened")
    data class Opened(val conversationId: String?, val seq: Long) : WebSyncEvent

    /** Submission phase of the composer the browser shows; [acceptedVersion] grows per accepted send. */
    @Serializable @SerialName("composer")
    data class Composer(
        val conversationId: String?, val phase: String, val acceptedVersion: Long,
        val seq: Long, val text: String, val editRevision: Long, val actionId: Long,
        val modelValid: Boolean, val generating: Boolean, val stopping: Boolean,
        val modelId: String, val models: Map<String, String>, val queue: List<JsonObject>,
        val attachments: List<JsonObject> = emptyList(),
        val controls: JsonObject? = null,
        val advanced: JsonObject? = null,
        val compact: JsonObject? = null,
    ) : WebSyncEvent

    @Serializable @SerialName("snackbar")
    data class Snackbar(val message: String) : WebSyncEvent

    @Serializable @SerialName("scroll_to_bottom")
    data class ScrollToBottom(val conversationId: String, val messageId: String, val seq: Long) : WebSyncEvent
}

@Serializable
internal data class WebConversation(
    val id: String,
    val title: String,
    val generating: Boolean,
    val unread: Boolean,
)

@Serializable
internal data class WebPathEntry(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
)

@Serializable
internal data class WebMessage(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
    val timestamp: Long,
    val modelName: String?,
    val text: WebText,
    val thoughts: WebText?,
    val thoughtTitle: String?,
    val thoughtTimeMs: Long?,
    val segments: List<WebSegment>,
    /** How the app lays out a model message; null for user messages. */
    val presentation: WebPresentation?,
)

@Serializable
internal data class WebSegment(
    val type: String,
    val content: WebText,
    val durationMs: Long?,
    val toolName: String?,
    val toolDisplayName: String?,
    val toolState: String?,
    val errorCode: String?,
)

@Serializable
internal data class WebText(val markdown: String, val math: List<WebMath> = emptyList())

@Serializable
internal data class WebMath(val tex: String, val display: Boolean)
