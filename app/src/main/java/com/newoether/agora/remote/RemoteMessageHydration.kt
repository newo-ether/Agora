package com.newoether.agora.remote

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.HydratedMessagePayloadLru
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
import com.newoether.agora.ui.chat.message.toRenderableMarkdownText
import com.newoether.agora.viewmodel.MessagePayloadProjector
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.intellij.markdown.ast.ASTNode

/** Page bodies prime the original payload cache before their stable list positions are published. */
internal class RemoteMessageHydration(
    private val state: StateFlow<RemoteState>,
    private val read: suspend (String, String?) -> RemoteConversationPage,
    private val failed: (Exception) -> Unit,
    private val image: (suspend (String, RemotePayloadRequest) -> com.newoether.agora.model.ToolImageAttachment)? = null,
    private val maxRecordBytes: Long = 8L * 1024 * 1024,
    projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) {
    private val cacheLock = Any()
    private var cacheOwner: String? = null
    // Packet admission must not evict its own small rows through the ordinary 16-row viewport cap.
    private var cache = HydratedMessagePayloadLru(maxEntries = Int.MAX_VALUE, maxWeightBytes = maxRecordBytes)
    private val revisions = mutableMapOf<String, List<String>>()
    private val records = linkedMapOf<String, Pair<String, RemoteMessage>>()
    private var recordBytes = 0L
    private var previousRuntime: RemoteRuntime? = null
    private var deltas = RemoteStreamDeltas()
    private val slots = Semaphore(2)
    private val projector = MessagePayloadProjector(projectionDispatcher)
    private data class Target(val group: RemoteMessageGroup, val runtime: RemoteRuntime?, val retry: Long)

    private fun checkOwner(owner: String) {
        if (state.value.owner != owner) throw CancellationException()
        if (cacheOwner != owner) {
            cacheOwner = owner
            cache = HydratedMessagePayloadLru(maxEntries = Int.MAX_VALUE, maxWeightBytes = maxRecordBytes)
            revisions.clear()
            records.clear()
            recordBytes = 0
            previousRuntime = null
            deltas = RemoteStreamDeltas()
        }
    }
    private fun weight(message: RemoteMessage) = 256L + 2L * (message.text.length.toLong() +
        (message.activity?.arguments?.length ?: 0) + (message.activity?.result?.length ?: 0) +
        (message.activity?.structuredResult?.toString()?.length ?: 0)) +
        32L * message.streamingTextDeltas.size + message.imageLinks.sumOf { 32L + 2L * it.length } +
        message.inlineImages.entries.sumOf { (link, image) -> 256L + 2L * (link.length + (image.attachment?.path?.length ?: 0)) }
    internal val retainedRecordBytes: Long get() = synchronized(cacheLock) { recordBytes }
    internal val retainedPayloadBytes: Long get() = synchronized(cacheLock) { cache.totalWeightBytes }
    fun resetStreaming() = synchronized(cacheLock) {
        previousRuntime = null
        deltas = RemoteStreamDeltas()
    }

    private fun rememberRecords(owner: String, page: RemoteConversationPage, live: Boolean, preserveImages: Boolean = true) = synchronized(cacheLock) {
        checkOwner(owner)
        val messages = if (live) deltas.apply(if (previousRuntime == null) emptyList() else records.values.map { it.second },
            page.messages, previousRuntime, page.runtime)
            else page.messages
        if (live) previousRuntime = page.runtime
        val nodes = page.nodes.associateBy { it.id }
        for (message in messages) {
            val node = nodes[message.id] ?: error("Filo page metadata is missing")
            val old = records.remove(message.id)
            old?.let { recordBytes -= weight(it.second) }
            val retained = if (preserveImages && old?.first == node.revision)
                message.copy(activity = message.activity?.copy(images = old.second.activity?.images.orEmpty()),
                    inlineImages = old.second.inlineImages)
                else message
            records[message.id] = node.revision to retained
            recordBytes += weight(retained)
            while (recordBytes > maxRecordBytes && records.isNotEmpty()) {
                val key = records.keys.first()
                recordBytes -= weight(records.remove(key)!!.second)
            }
        }
    }

    private fun target(owner: String, id: String): Target? {
        val snapshot = state.value
        if (!snapshot.hydrationEnabled || snapshot.owner != owner) return null
        val group = snapshot.messageGroups.firstOrNull { it.stub.id == id } ?: return null
        return Target(group, snapshot.runtime.takeIf { snapshot.messageGroups.lastOrNull()?.stub?.id == id },
            snapshot.hydrationRevision)
    }

    fun cachedMessage(owner: String, group: RemoteMessageGroup): ChatMessage? = synchronized(cacheLock) {
        checkOwner(owner)
        cache[group.stub.id]?.takeIf { revisions[group.stub.id] == group.revision }
            ?.copy(status = group.stub.status, parentId = group.stub.parentId, displayPageId = group.stub.displayPageId)
    }

    private fun remember(owner: String, group: RemoteMessageGroup, message: ChatMessage) = synchronized(cacheLock) {
        checkOwner(owner)
        cache.put(message)
        revisions[group.stub.id] = group.revision
    }

    private fun cachedRecords(owner: String, group: RemoteMessageGroup): List<RemoteMessage>? = synchronized(cacheLock) {
        checkOwner(owner)
        group.nodes.map { node ->
            records[node.id]?.takeIf { it.first == node.revision }?.second ?: return null
        }
    }

    private suspend fun project(group: RemoteMessageGroup, messages: List<RemoteMessage>): ChatMessage {
        val message = projector.project {
            val imageKeys = group.nodes.filter { it.activity?.hasImage == true }.associate { it.id to it.revision }
            val projected = projectRemoteMessages(messages.map { it.copy(groupId = group.stub.id) }).firstOrNull()
                ?.copy(id = group.stub.id, parentId = group.stub.parentId, status = group.stub.status,
                    displayPageId = group.stub.displayPageId) ?: group.stub
            projected.copy(segments = projected.segments?.map { segment ->
                segment.copy(toolImageRequestKey = imageKeys[segment.toolCallId])
            })
        }
        if (message.participant != Participant.MODEL ||
            message.status !in setOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED)) return message
        val texts = buildSet {
            add(message.text)
            message.thoughts?.let(::add)
            mergeAdjacentSegments(message.segments.orEmpty())
                .filter { it.type == "answer" || it.type == "thought" }.forEach { add(it.content) }
        }.filter { it.isNotBlank() }
        val prepared = linkedMapOf<String, State.Success>()
        var bytes = 0L
        for (text in texts) for (inlineMath in listOf(false, true)) {
            currentCoroutineContext().ensureActive()
            val markdown = projector.project { text.toRenderableMarkdownText(inlineMath) }
            if (markdown in prepared) continue
            // The public parser retains its own dispatcher inside the existing bounded permit.
            val parsed = projector.project {
                parseMarkdownFlow(markdown).first { it !is State.Loading }
            }
            if (parsed !is State.Success) continue
            bytes += projector.project {
                val nodes = java.util.ArrayDeque<ASTNode>()
                nodes.add(parsed.node)
                var weight = 256L + markdown.length * 2L
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeLast()
                    weight += 256L
                    nodes.addAll(node.children)
                }
                weight
            }
            prepared[markdown] = parsed
        }
        return message.copy(preparedMarkdown = prepared, preparedMarkdownBytes = bytes)
    }

    suspend fun accept(owner: String, page: RemoteConversationPage, groups: List<RemoteMessageGroup>, live: Boolean = true) {
        val firstStream = synchronized(cacheLock) { live && previousRuntime == null }
        rememberRecords(owner, page, live)
        val ids = page.nodes.mapTo(HashSet()) { it.id }
        for (group in groups) {
            if (group.nodes.none { it.id in ids }) continue
            if (!firstStream && cachedMessage(owner, group) != null) continue
            val body = cachedRecords(owner, group) ?: continue
            val message = project(group, body)
            currentCoroutineContext().ensureActive()
            remember(owner, group, message)
        }
    }

    private suspend fun loadRecords(owner: String, group: RemoteMessageGroup): List<RemoteMessage> = slots.withPermit {
        cachedRecords(owner, group)?.let { return@withPermit it }
        // A native page bookmark returns a bounded body batch, never one HTTP call per message.
        var cursor = group.nodes.lastOrNull()?.pageCursor
        val visited = mutableSetOf<String?>()
        val found = mutableMapOf<String, RemoteMessage>()
        val wanted = group.nodes.mapTo(HashSet()) { it.id }
        do {
            currentCoroutineContext().ensureActive()
            if (state.value.owner != owner || !state.value.hydrationEnabled) throw CancellationException()
            require(visited.add(cursor)) { "Filo history cursor did not advance" }
            val page = read(owner, cursor)
            currentCoroutineContext().ensureActive()
            rememberRecords(owner, page, live = false)
            page.messages.filter { it.id in wanted }.forEach { found[it.id] = it }
            cursor = page.nextCursor
        } while (found.size != wanted.size && cursor != null)
        require(found.size == wanted.size) { "Filo page changed; reload history" }
        group.nodes.map { found.getValue(it.id) }
    }

    fun observeMessage(owner: String, id: String): Flow<ChatMessage?> = channelFlow {
        state.map { target(owner, id) }.distinctUntilChanged().collectLatest { target ->
            if (target == null) { send(null); return@collectLatest }
            val group = target.group
            if (group.nodes.isEmpty()) { send(group.stub); return@collectLatest }
            val cached = cachedMessage(owner, group)
            if (cached != null) send(cached)
            try {
                val needsImages = group.nodes.any { it.imageCount > 0 } &&
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        (group.nodes.sumOf { it.imageCount } > cached?.markdownImages.orEmpty().size) ||
                            cached?.markdownImages.orEmpty().values.any { it.attachment?.path?.let { path -> java.io.File(path).isFile } != true }
                    }
                if (cached != null && !needsImages) return@collectLatest
                val records = loadRecords(owner, group)
                val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    records.map { message -> message.copy(inlineImages = message.inlineImages.mapValues { (_, value) ->
                        value.takeIf { it.attachment?.path?.let { path -> java.io.File(path).isFile } == true }
                            ?: MarkdownImage()
                    }) }.toMutableList()
                }
                suspend fun publish() {
                    currentCoroutineContext().ensureActive()
                    if (target(owner, id)?.group?.revision != group.revision) throw CancellationException()
                    val projected = project(group, fresh)
                    currentCoroutineContext().ensureActive()
                    if (target(owner, id)?.group?.revision != group.revision) throw CancellationException()
                    rememberRecords(owner, RemoteConversationPage(fresh.toList(), null, emptyList(), nodes = group.nodes),
                        live = false, preserveImages = false)
                    remember(owner, group, projected)
                    send(projected)
                }
                // Publish fixed pending slots before waiting for any authenticated image bytes.
                if (fresh.any { it.imageLinks.isNotEmpty() }) publish()
                for ((position, message) in fresh.toList().withIndex()) {
                    var hydrated = message
                    if (image != null) {
                        val request = group.requests.first { it.id == message.id }
                        suspend fun load(index: Int? = null) = try {
                            image.invoke(owner, request.copy(imageIndex = index))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failed(error); null }
                        val inline = message.inlineImages.toMutableMap()
                        for ((index, link) in message.imageLinks.withIndex()) {
                            val attachment = inline[link]?.attachment ?: load(index)
                            inline[link] = MarkdownImage(attachment, failed = attachment == null)
                            hydrated = hydrated.copy(inlineImages = inline.toMap())
                            fresh[position] = hydrated
                            publish()
                        }
                    }
                    fresh[position] = hydrated
                }
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error); if (cached == null) send(null) }
        }
    }

    /** Only an expanded preview admits image bytes; topology and text observation never do. */
    suspend fun loadToolImage(owner: String, id: String, revision: String): com.newoether.agora.model.ToolImageAttachment {
        fun validate() {
            val snapshot = state.value
            if (snapshot.owner != owner || !snapshot.hydrationEnabled || snapshot.messageGroups.none { group ->
                group.nodes.any { it.id == id && it.revision == revision && it.activity?.hasImage == true }
            }) throw CancellationException()
        }
        currentCoroutineContext().ensureActive()
        validate()
        val attachment = try {
            image?.invoke(owner, RemotePayloadRequest(id, revision))
                ?: throw java.io.IOException("Image storage is unavailable")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            validate()
            failed(error)
            throw error
        }
        currentCoroutineContext().ensureActive()
        validate()
        return attachment
    }

    /** Original Search requests bounded batches without downloading image bytes. */
    suspend fun loadMessages(owner: String, ids: List<String>): List<ChatMessage> = buildList {
        for (id in ids.distinct()) {
            val group = target(owner, id)?.group ?: continue
            val existing = cachedMessage(owner, group)
            if (existing != null) { add(existing); continue }
            val message = project(group, loadRecords(owner, group))
            currentCoroutineContext().ensureActive()
            remember(owner, group, message)
            add(message)
        }
    }
}
