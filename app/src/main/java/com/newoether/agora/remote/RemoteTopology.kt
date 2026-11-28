package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.Serializable

@Serializable
internal data class RemoteMessageNode(
    val id: String, val turnId: String, val clientId: String?, val role: String, val timestamp: Long?,
    val revision: String, val textLength: Int,
    val groupId: String? = null, val nativeId: String? = null,
    val textOffset: Int = 0, val textContinues: Boolean = false,
    val activity: RemoteNodeActivity? = null, val hasContent: Boolean? = null,
    val imageCount: Int = 0,
    val error: Boolean = false,
    @kotlinx.serialization.Transient val displayPageId: String? = null,
    @kotlinx.serialization.Transient val displayGroupId: String? = null,
    @kotlinx.serialization.Transient val pageCursor: String? = null,
)
@Serializable
internal data class RemoteNodeActivity(val type: String, val state: String? = null, val durationMs: Long? = null,
    val hasImage: Boolean = false)
@Serializable
internal data class RemotePayloadRequest(val id: String, val revision: String, val imageIndex: Int? = null)

internal data class RemoteMessageGroup(val stub: ChatMessage, val nodes: List<RemoteMessageNode>) {
    val revision: List<String> get() = nodes.map { it.revision }
    val requests: List<RemotePayloadRequest> get() = nodes.map { RemotePayloadRequest(it.id, it.revision) }
}

/** Admitted page structure stays resident. Payload loading never changes its IDs or positions. */
internal fun projectRemoteTopology(nodes: List<RemoteMessageNode>, runtime: RemoteRuntime?): List<RemoteMessageGroup> = buildList {
    val projectedIds = HashSet<String>()
    var index = 0
    while (index < nodes.size) {
        val first = nodes[index++]
        val group = mutableListOf(first)
        while (index < nodes.size) {
            val next = nodes[index]
            val same = if (first.role == "assistant") next.role == "assistant" && next.turnId == first.turnId
            else next.role == "user" && (next.nativeId ?: next.id) == (first.nativeId ?: first.id)
            if (!same || next.displayGroupId != first.displayGroupId) break
            group += next
            index++
        }
        if (first.role == "assistant" && group.none { it.hasContent ?: (it.textLength > 0 || it.activity?.type == "tool") }) continue
        val preferredId = first.displayGroupId ?: first.groupId ?: first.nativeId ?: first.id
        // A late native user can split an admitted assistant group. Keep the
        // earlier fragment's key and give the later fragment its own native key.
        val id = if (projectedIds.add(preferredId)) preferredId else first.id.also { projectedIds.add(it) }
        add(RemoteMessageGroup(ChatMessage(id = id, parentId = lastOrNull()?.stub?.takeIf {
                it.displayPageId == first.displayPageId
            }?.id, text = "",
            participant = if (first.role == "user") Participant.USER else Participant.MODEL,
            timestamp = first.timestamp ?: 0L, modelName = "Codex", runId = first.turnId,
            displayPageId = first.displayPageId,
            status = if (group.any { it.error }) MessageStatus.ERROR else MessageStatus.SUCCESS), group))
    }
    if (runtime?.isRunning == true && runtime.activeTurnId != null &&
        (runtime.activeTurnHasUserMessage || nodes.any { it.role == "user" && it.turnId == runtime.activeTurnId })) {
        val tail = lastOrNull()
        // Preserve the native failure through hydration and stale active snapshots.
        if (tail?.stub?.runId == runtime.activeTurnId && tail.stub.status == MessageStatus.ERROR) return@buildList
        if (tail?.stub?.participant == Participant.MODEL && tail.stub.runId == runtime.activeTurnId) {
            val status = when (tail.nodes.lastOrNull()?.activity?.type) {
                "thought" -> MessageStatus.THINKING
                "tool" -> MessageStatus.TOOL_CALLING
                else -> MessageStatus.SENDING
            }
            set(lastIndex, tail.copy(stub = tail.stub.copy(status = status, modelName = runtime.model ?: "Codex")))
        } else add(RemoteMessageGroup(ChatMessage(id = "remote-active-${runtime.activeTurnId}",
            parentId = tail?.stub?.id, text = "", participant = Participant.MODEL,
            timestamp = tail?.stub?.timestamp ?: 0, modelName = runtime.model ?: "Codex",
            runId = runtime.activeTurnId, status = MessageStatus.SENDING,
            displayPageId = tail?.stub?.displayPageId), emptyList()))
    }
}

/** Assign page fragments once. Prepending never merges into an already rendered fragment. */
internal fun admitRemotePage(
    previous: List<RemoteMessageNode>, page: RemoteConversationPage, older: Boolean = false,
): List<RemoteMessageNode> {
    require(page.nodes.map { it.id } == page.messages.map { it.id }) { "Filo page metadata is missing" }
    return admitRemoteNodes(previous, page.nodes.map { it.copy(pageCursor = it.pageCursor ?: page.pageCursor) }, older)
}

internal fun admitRemoteNodes(
    previous: List<RemoteMessageNode>, incoming: List<RemoteMessageNode>, older: Boolean = false,
): List<RemoteMessageNode> {
    val known = previous.associateBy { it.id }
    var preceding = if (older) null else previous.lastOrNull()
    var pageId = preceding?.displayPageId
    var pageCount = previous.count { it.displayPageId == pageId }
    val fresh = incoming.map { node ->
        val old = known[node.id]
        val admitted = if (old != null) node.copy(
            displayPageId = old.displayPageId, displayGroupId = old.displayGroupId, pageCursor = node.pageCursor,
        ) else {
            if (pageId == null || pageCount >= 128) {
                pageId = node.id; pageCount = 0; preceding = null
            }
            val sameGroup = preceding?.let {
                it.displayPageId == pageId && it.role == node.role &&
                    (if (node.role == "assistant") it.turnId == node.turnId
                    else (it.nativeId ?: it.id) == (node.nativeId ?: node.id))
            } == true
            pageCount++
            node.copy(displayPageId = pageId, displayGroupId = if (sameGroup) preceding!!.displayGroupId else node.id,
                pageCursor = node.pageCursor)
        }
        preceding = admitted
        pageId = admitted.displayPageId
        admitted
    }
    if (older) return fresh.filterNot { it.id in known } + previous
    val boundary = fresh.firstOrNull()?.id ?: return previous
    val index = previous.indexOfFirst { it.id == boundary }
    return (previous.take(if (index >= 0) index else previous.size) + fresh).distinctBy { it.id }
}
