package com.newoether.agora.webui

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    val connectionId: String? = null,
    val requestId: String? = null,
    val alwaysAllow: Boolean? = null,
    val answers: Map<String, WebInteractionAnswer>? = null,
    /** The one message a row-level command (for example an edit) acts on. */
    val messageId: String? = null,
    val direction: Int = 0,
    val branchAnchorMessageId: String? = null,
    val branchAnchorParentId: String? = null,
    val branchIndex: Int = 0,
    val totalBranches: Int = 1,
)

@Serializable
internal data class WebInteractionAnswer(
    val choices: List<String> = emptyList(),
    val text: String? = null,
    val answered: Boolean = false,
)

@Serializable
internal sealed interface WebSyncEvent {
    @Serializable @SerialName("connection")
    data class Connection(val connectionId: String) : WebSyncEvent
    @Serializable @SerialName("search")
    data class Search(
        val connectionId: String, val revision: Long, val query: String,
        val searching: Boolean, val items: List<JsonObject> = emptyList(), val failed: Boolean = false,
    ) : WebSyncEvent
    @Serializable @SerialName("conversation_search")
    data class ConversationSearch(
        val connectionId: String, val conversationId: String, val seq: Long,
        val revision: Long, val query: String, val searching: Boolean,
        val matches: List<JsonObject> = emptyList(), val failed: Boolean = false,
    ) : WebSyncEvent
    @Serializable @SerialName("interactions")
    data class Interactions(val conversationId: String?, val seq: Long, val actionId: Long, val items: List<JsonObject>) : WebSyncEvent
    @Serializable @SerialName("todo")
    data class Todo(
        val conversationId: String?, val seq: Long,
        val items: List<com.newoether.agora.model.ConversationTodoItem>,
    ) : WebSyncEvent
    @Serializable @SerialName("subagents")
    data class Subagents(
        val conversationId: String?, val seq: Long, val parentConversationId: String?,
        val items: List<com.newoether.agora.model.SubagentSummary>,
    ) : WebSyncEvent
    @Serializable @SerialName("conversations")
    data class Conversations(val items: List<WebConversation>, val hasMore: Boolean = false, val limit: Int = WebUiSync.DRAWER_PAGE_SIZE) : WebSyncEvent

    /** The selected branch of the open conversation, without message bodies. */
    @Serializable @SerialName("path")
    data class Path(
        val conversationId: String,
        val messages: List<WebPathEntry>,
        val generating: Boolean,
        val seq: Long = 0L,
    ) : WebSyncEvent

    @Serializable @SerialName("row_action")
    data class RowAction(val conversationId: String, val seq: Long, val actionId: Long, val path: List<WebPathEntry>, val success: Boolean) : WebSyncEvent

    /** The visual branch replacement request owned by this browser's ordinary ChatClient. */
    @Serializable @SerialName("branch_replacement")
    data class BranchReplacement(
        val connectionId: String, val conversationId: String, val seq: Long, val request: JsonObject?,
    ) : WebSyncEvent
    /** The body of one watched row. */
    @Serializable @SerialName("payload")
    data class Payload(val conversationId: String, val message: WebMessage, val seq: Long = 0L) : WebSyncEvent

    /** The in-flight message; it replaces the durable body of the row with the same id. */
    @Serializable @SerialName("streaming")
    data class Streaming(val conversationId: String, val message: WebMessage?, val seq: Long = 0L) : WebSyncEvent

    /**
     * The next frame of the in-flight message [messageId] as a [webJsonPatch] against the frame
     * this connection sent last. A new message, or the end of streaming, is a full [Streaming].
     */
    @Serializable @SerialName("streaming_patch")
    data class StreamingPatch(
        val conversationId: String,
        val messageId: String,
        val patch: JsonObject,
        val seq: Long = 0L,
    ) : WebSyncEvent

    @Serializable @SerialName("deleted")
    data class Deleted(val conversationId: String, val seq: Long = 0L) : WebSyncEvent

    /**
     * How the phone's conversation owner answered this connection's delete request. [actionId]
     * echoes the request so only the browser that asked settles its own dialog; a delete that
     * another client started reaches this browser as [Deleted] and a shorter list instead.
     */
    @Serializable @SerialName("conversation_delete")
    data class ConversationDelete(val conversationId: String, val actionId: Long, val success: Boolean) : WebSyncEvent

    /** The phone's display settings and live-timer strings; sent first and on each change. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("display")
    data class Display(
        val toolCallDisplayMode: String,
        val thinkingSegmentDisplayMode: String,
        val autoExpandActiveGroup: Boolean,
        val autoWrapCodeBlocks: Boolean,
        val blurEffectsEnabled: Boolean,
        val reduceMotion: Boolean,
        val liveThinking: WebLiveTimerStrings,
        @EncodeDefault val stickToBottom: Boolean = true,
        @EncodeDefault val subagentCapsuleDisplayMode: String = com.newoether.agora.data.SubagentCapsuleDisplayModes.DEFAULT,
    ) : WebSyncEvent

    @Serializable @SerialName("load_failed")
    data class LoadFailed(val conversationId: String, val seq: Long = 0L) : WebSyncEvent

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
        val systemPrompt: JsonObject? = null,
    ) : WebSyncEvent

    /**
     * The context window of the conversation one connection shows. Every connection prices its own
     * target, so two browsers never share a projection and neither shows the phone's figure.
     * Labels come from the phone's own context owners; the client only draws.
     */
    @Serializable @SerialName("context")
    data class Context(
        val conversationId: String?,
        val seq: Long,
        val tokenBudget: Int,
        val budgetLabel: String,
        val estimatedTokens: Int? = null,
        val estimatedLabel: String? = null,
        val parts: List<WebContextPart> = emptyList(),
        val compactThresholdPercent: Int = 90,
        val compactEnabled: Boolean = true,
        val overCompactThreshold: Boolean = false,
        val loading: Boolean = false,
        val failed: Boolean = false,
    ) : WebSyncEvent

    @Serializable @SerialName("snackbar")
    data class Snackbar(val message: String) : WebSyncEvent

    @Serializable @SerialName("page_action")
    data class PageAction(
        val conversationId: String?, val seq: Long, val actionId: Long,
        val action: String, val success: Boolean, val text: String? = null,
    ) : WebSyncEvent

    @Serializable @SerialName("scroll_to_bottom")
    data class ScrollToBottom(val conversationId: String, val messageId: String, val seq: Long) : WebSyncEvent
}

/** One labelled part of the context window, in the order the provider is given them. */
@Serializable
internal data class WebContextPart(val key: String, val tokens: Int, val label: String)

@Serializable
internal data class WebConversation(
    val id: String,
    val title: String,
    val generating: Boolean,
    val unread: Boolean,
    val isPinned: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class WebPathEntry(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
    val showBranchSelector: Boolean = false,
    @EncodeDefault val branchIndex: Int = 0,
    @EncodeDefault val totalBranches: Int = 1,
    val branchAnchorParentId: String? = null,
    val branchAnchorMessageId: String? = null,
)

@Serializable
internal data class WebMessage(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
    val timestamp: Long,
    val modelName: String?,
    /** The model as MessageInfoDialog shows it: modelDisplayName with the phone's aliases. */
    val modelDisplay: String? = null,
    val text: WebText,
    /** How the app lays out a model message; null for user messages. */
    val presentation: WebPresentation?,
    /** The row holding a context-compact summary; the phone draws it as a pill instead of as text. */
    val compact: Boolean = false,
    /** Where an automatic user message came from; the phone labels its bubble with this. */
    val source: WebMessageSource? = null,
    /** The token figures of the message info dialog, projected by the phone's own owner. */
    val usage: WebTokenUsage? = null,
    val attachments: List<JsonObject> = emptyList(),
    /** Offline source/detail text cost for browser history paging, never Provider usage. */
    val loadTokens: Int = 0,
)

/** The origin of an automatic input, including optional compact Subagent completion display. */
@Serializable
internal data class WebMessageSource(
    val kind: String,
    val askUser: List<WebAskUserItem> = emptyList(),
    val subagentResult: com.newoether.agora.model.MessageSource.SubagentResult? = null,
)

/** One asked question. A null [answer] renders the localized unanswered label, as on the phone. */
@Serializable
internal data class WebAskUserItem(val question: String, val answer: String? = null)

/** Token usage of one model row; a figure is null when its provider never reported it. */
@Serializable
internal data class WebTokenUsage(
    val input: Int?,
    val cachedInput: Int?,
    val output: Int?,
    val tokensPerSecond: Double?,
    val timeToFirstTokenMs: Long?,
    val totalGenerationMs: Long?,
)


@Serializable
internal data class WebText(
    val markdown: String,
    val math: List<WebMath> = emptyList(),
    val sourceMap: List<List<Int>> = emptyList(),
    val citations: List<WebCitationMarker> = emptyList(),
)

@Serializable
internal data class WebCitationMarker(
    val token: String,
    val label: String,
    val sources: List<WebCitation>,
    val accessibilityLabel: String = "",
)

@Serializable
internal data class WebCitation(
    val id: String,
    val title: String,
    val url: String?,
    val fileName: String?,
    val location: String?,
    val excerpt: String?,
)

@Serializable
internal data class WebMath(val tex: String, val display: Boolean)
