package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ThinkingResolution
import com.newoether.agora.data.thinkingCapabilityForSelectedModel
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.util.Constants
import com.newoether.agora.ui.components.parseLatexSpans
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ConversationMessagePayloadHydration
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiState
import com.newoether.agora.viewmodel.toUiChatMessageStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat state mirror and command entry for one WebUI connection.
 *
 * Each connection chooses its own conversation through its [WebUiChatSession]; nothing here
 * changes what the phone shows. Send and Stop go to that session's runtime client. The
 * data follows the app's own loading rules: the list uses the drawer's narrow projection, opening
 * a conversation runs the same runtime recovery the app runs, the open conversation sends only
 * its selected-branch topology, and a message body is read only while the browser watches that
 * row. Room stays the only source; this class keeps no copy of the graph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WebUiSync(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val hydration: ConversationMessagePayloadHydration,
    private val customProviders: StateFlow<List<CustomProviderConfig>>,
    private val display: Flow<WebDisplayContext>,
    /** Builds the [WebUiChatSession] of one connection inside that connection's scope. */
    private val openChatSession: (CoroutineScope) -> WebUiChatSession,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val connections = java.util.concurrent.ConcurrentHashMap<String, Pair<String, WebUiChatSession>>()

    suspend fun upload(
        login: String, connectionId: String, seq: Long, name: String, mime: String?, forcedType: String?,
        size: Long?, input: io.ktor.utils.io.ByteReadChannel,
    ): io.ktor.http.HttpStatusCode {
        val session = connections[connectionId]?.takeIf { it.first == login }?.second
            ?: return io.ktor.http.HttpStatusCode.NotFound
        return session.upload(seq, name, mime, forcedType, size, input)
    }

    suspend fun previewAttachment(
        login: String, connectionId: String, seq: Long, id: String, kind: String, index: Int,
        consume: suspend (java.io.File, String) -> Unit,
    ): Boolean {
        val session = connections[connectionId]?.takeIf { it.first == login }?.second ?: return false
        return session.previewAttachment(seq, id, kind, index, consume)
    }
    /**
     * Serves one connection until [incoming] closes. Commands arrive as JSON text; every event
     * is handed to [send] from a single coroutine, so [send] needs no locking of its own.
     */
    suspend fun serve(login: String, incoming: ReceiveChannel<String>, send: suspend (String) -> Unit) =
        coroutineScope {
            // Rendezvous: a producer waits until the sender takes its event, so a slow browser
            // slows the producers instead of growing a queue.
            val outbound = Channel<WebSyncEvent>()
            launch { for (event in outbound) send(json.encodeToString(WebSyncEvent.serializer(), event)) }
            val list = combine(
                conversations.getAllConversations(),
                registry.activeConversationIds,
            ) { items, active -> items to active }
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            val displayContext = display.distinctUntilChanged()
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { displayContext.collect { outbound.send(it.toEvent()) } }
            launch {
                list.map { (items, active) ->
                    WebSyncEvent.Conversations(
                        items.map { it.toWeb(generating = it.id in active) },
                    )
                }
                    .distinctUntilChanged()
                    .collect { outbound.send(it) }
            }
            val watched = MutableStateFlow<Set<String>>(emptySet())
            val session = openChatSession(this)
            val connectionId = java.util.UUID.randomUUID().toString()
            val connectionJob = coroutineContext[Job]!!
            try {
                session.start()
                connections[connectionId] = login to session
                outbound.send(WebSyncEvent.Connection(connectionId))
                launch { session.snackbars.collect { outbound.send(WebSyncEvent.Snackbar(it)) } }
                launch { session.scrollRequests.collect { outbound.send(it) } }
                // The session decides what is open; a runtime move (New Chat send, deletion) is
                // announced before any event of the new target so the browser can follow it.
                launch {
                    session.openTarget.collectLatest { target ->
                        if (target.movedByServer) {
                            outbound.send(WebSyncEvent.Opened(target.conversationId, target.browserSeq))
                        }
                        coroutineScope {
                            launch {
                                session.composerState
                                    .combine(customProviders) { state, _ -> state }
                                    .filter { it.conversationId == target.conversationId && it.seq == target.browserSeq }
                                    .map {
                                        WebSyncEvent.Composer(
                                            it.conversationId, it.snapshot.phase.name, it.snapshot.acceptedVersion,
                                            it.seq, it.text, it.editRevision, it.actionId, it.modelValid,
                                            it.generating, it.stopping,
                                            it.modelId, it.models, it.queue.map { queued ->
                                                buildJsonObject {
                                                    put("id", queued.id)
                                                    put("text", queued.text)
                                                    put("attachmentCount", queued.attachments.size)
                                                }
                                            },
                                            it.attachments.map { attachment ->
                                                buildJsonObject {
                                                    put("id", attachment.localId)
                                                    put("type", attachment.type)
                                                    put("name", attachment.fileName)
                                                    put("state", attachment.importState.name)
                                                    put("storage", attachment.storage.name)
                                                    put("unavailable", attachment.unavailable)
                                                    put("pageCount", attachment.pageCount)
                                                    put("durationMs", attachment.videoDurationMs)
                                                    put("frameCount", attachment.frameCount)
                                                    put("intervalMs", attachment.sliceIntervalMs)
                                                    put("staged", attachment.localPath != null)
                                                    if (attachment.type == "file" && attachment.importState == com.newoether.agora.model.AttachmentImportState.READY &&
                                                        attachment.storage == com.newoether.agora.model.AttachmentStorage.APP_PRIVATE && !attachment.unavailable) {
                                                        put("text", attachment.preparedText)
                                                    }
                                                    attachment.selectedPages?.let { pages ->
                                                        put("selectedPages", JsonArray(pages.sorted().map(::JsonPrimitive)))
                                                    }
                                                    put("pagePreviewCount", attachment.preRenderedPaths?.size ?: 0)
                                                    put("framePreviewCount", attachment.processedFrames?.size ?: 0)
                                                    attachment.videoDurationMs?.let { duration ->
                                                        put("defaultFrameCount", com.newoether.agora.ui.chat.VideoSliceDefaults.defaultFrameCount(duration))
                                                    }
                                                    it.pdfProgress[attachment.localId]?.let { (done, total) ->
                                                        put("previewDone", done)
                                                        put("previewTotal", total)
                                                    }
                                                }
                                            },
                                            it.controls?.let { controls ->
                                                val model = ModelId.parse(it.modelId)
                                                val capability = thinkingCapabilityForSelectedModel(it.modelId, customProviders.value)
                                                val thinking = ThinkingResolution.resolve(capability, controls.thinkingEnabled,
                                                    controls.thinkingLevel, controls.thinkingBudgetEnabled, controls.thinkingBudgetTokens)
                                                buildJsonObject {
                                                    put("isGemini", it.modelValid && providerDisplayName(model.providerName, customProviders.value).equals("google", ignoreCase = true))
                                                    put("thinkingCanDisable", capability.canDisableThinking)
                                                    put("thinkingSupportsBudget", capability.supportsThinkingBudget)
                                                    put("thinkingEfforts", JsonArray(capability.supportedEfforts.map(::JsonPrimitive)))
                                                    put("thinkingBudgetPresets", JsonArray(ThinkingLevels.budgetPresets.map(::JsonPrimitive)))
                                                    put("displayedThinkingEnabled", thinking.enabled)
                                                    put("displayedThinkingLevel", thinking.effort ?: capability.nearestEffort(controls.thinkingLevel) ?: controls.thinkingLevel)
                                                    put("displayedThinkingBudgetEnabled", thinking.budgetTokens != null)
                                                    put("displayedThinkingBudgetTokens", thinking.budgetTokens ?: controls.thinkingBudgetTokens)
                                                    put("serviceTiers", JsonArray(OpenAiServiceTiers.availableTiers(model.modelName, model.providerName == Constants.PROVIDER_OPENAI).map(::JsonPrimitive)))
                                                    put("displayedServiceTier", OpenAiServiceTiers.mappedTier(controls.openAiServiceTierState.tier, model.modelName, model.providerName == Constants.PROVIDER_OPENAI))
                                                    put("codeExecutionEnabled", controls.codeExecutionEnabled)
                                                    put("googleSearchEnabled", controls.googleSearchEnabled)
                                                    put("thinkingEnabled", controls.thinkingEnabled)
                                                    put("thinkingLevel", controls.thinkingLevel)
                                                    put("thinkingBudgetEnabled", controls.thinkingBudgetEnabled)
                                                    put("thinkingBudgetTokens", controls.thinkingBudgetTokens)
                                                    put("openAiWebSearchAvailable", controls.openAiWebSearchAvailable)
                                                    put("openAiWebSearchEnabled", controls.openAiWebSearchEnabled)
                                                    put("openAiServiceTierAvailable", controls.openAiServiceTierState.available)
                                                    put("openAiServiceTierEnabled", controls.openAiServiceTierState.enabled)
                                                    put("openAiServiceTier", controls.openAiServiceTierState.tier)
                                                    put("webSearchAvailable", controls.webSearchAvailable)
                                                    put("webSearchEnabled", controls.webSearchEnabled)
                                                    put("shellAvailable", controls.shellAvailable)
                                                    put("shellEnabled", controls.shellEnabled)
                                                    put("showLowContextMode", controls.showLowContextMode)
                                                    put("lowContextModeEnabled", controls.lowContextModeEnabled)
                                                    put("contextWindow", controls.contextWindow)
                                                }
                                            },
                                            buildJsonObject {
                                                put("overrides", json.encodeToJsonElement(com.newoether.agora.data.ConversationSettings.serializer(), it.generationParameters.copy(contextWindow = it.generationParameters.contextWindow?.let(com.newoether.agora.model.ContextBudget::normalize))))
                                                put("defaults", json.encodeToJsonElement(com.newoether.agora.data.ConversationSettings.serializer(), it.generationDefaults.copy(contextWindow = it.generationDefaults.contextWindow?.let(com.newoether.agora.model.ContextBudget::normalize))))
                                                put("contextPresets", JsonArray(com.newoether.agora.model.ContextBudget.PRESETS.map(::JsonPrimitive)))
                                                put("contextLabels", JsonArray(com.newoether.agora.model.ContextBudget.PRESETS.map { value -> JsonPrimitive(com.newoether.agora.model.ContextBudget.compactLabel(value)) }))
                                                put("contextOverrideLabel", it.generationParameters.contextWindow?.let { value -> com.newoether.agora.model.ContextBudget.compactLabel(com.newoether.agora.model.ContextBudget.normalize(value)) })
                                                put("contextDefaultLabel", com.newoether.agora.model.ContextBudget.compactLabel(com.newoether.agora.model.ContextBudget.normalize(it.generationDefaults.contextWindow)))
                                                put("maxTokensPresets", JsonArray(com.newoether.agora.ui.chat.advancedMaxTokensPresets.map(::JsonPrimitive)))
                                            },
                                            it.compactDefaults?.let { request -> buildJsonObject {
                                                put("modelId", request.model)
                                                put("prompt", request.prompt)
                                                put("retainCount", request.retainLogicalMessages)
                                                put("compacting", it.compacting)
                                            } },
                                        )
                                    }.distinctUntilChanged().collect { outbound.send(it) }
                            }
                            target.conversationId?.let { id ->
                                openConversation(id, list.map { it.first }, watched, displayContext, outbound)
                            }
                        }
                    }
                }
                for (text in incoming) {
                    val command = runCatching {
                        json.decodeFromString(WebSyncCommand.serializer(), text)
                    }.getOrNull() ?: continue
                    when (command.type) {
                        COMMAND_OPEN -> {
                            watched.value = emptySet()
                            session.open(command.conversationId, command.seq)
                        }
                        COMMAND_WATCH -> watched.value = command.messageIds.take(MAX_WATCHED).toSet()
                        COMMAND_DRAFT -> session.edit(command.text.orEmpty(), command.revision, command.seq)
                        COMMAND_SEND -> session.send(command.text.orEmpty(), command.seq, command.actionId)
                        COMMAND_CANCEL_WAITING -> session.cancelWaiting(command.seq, command.actionId)
                        COMMAND_STOP -> session.stop(command.seq)
                        COMMAND_MODEL -> session.selectModel(command.modelId.orEmpty(), command.seq, command.actionId)
                        COMMAND_REMOVE_QUEUED -> session.removeQueued(command.queuedId.orEmpty(), command.seq)
                        COMMAND_SEND_QUEUED -> session.sendQueued(command.seq, command.actionId)
                        "attachment_remove", "attachment_retry", "attachment_pdf", "attachment_video" ->
                            session.attachmentCommand(command)
                        "setting" -> session.settingCommand(command)
                        "advanced", "compact" -> session.editorCommand(command)
                    }
                }
            } finally {
                connections.remove(connectionId)
                withContext(kotlinx.coroutines.NonCancellable) {
                    session.endUploads()
                    val children = connectionJob.children.toList()
                    children.forEach { it.cancel() }
                    children.forEach { it.join() }
                    session.close()
                }
            }
            coroutineContext.cancelChildren()
        }

    private suspend fun openConversation(
        id: String,
        list: Flow<List<ChatConversation>>,
        watched: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
    ) {
        // The web can only open what its list showed; a row that is gone was deleted.
        if (list.first().none { it.id == id }) {
            outbound.send(WebSyncEvent.Deleted(id))
            return
        }
        // A failure in any collector ends this conversation only, never the connection.
        try {
            coroutineScope { observeConversation(id, list, watched, display, outbound) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e(TAG, "WebUI failed to load a conversation", error)
            outbound.send(WebSyncEvent.LoadFailed(id))
        }
    }

    private suspend fun CoroutineScope.observeConversation(
        id: String,
        list: Flow<List<ChatConversation>>,
        watched: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
    ) {
        executionCoordinator.tryWithConversationLock(id) {
            conversations.recoverConversationRuntime(id)
        }
        val state = registry.getOrCreate(id)
        val selectedChildren = list.map { items ->
            val row = items.firstOrNull { it.id == id }
            if (row == null) {
                outbound.send(WebSyncEvent.Deleted(id))
                this@observeConversation.cancel()
            }
            decodeSelectedChildren(row?.selectedBranchesJson)
        }.distinctUntilChanged()
        val stubs = conversations.observeMessageTopology(id)
            .distinctUntilChanged()
            .map { topology -> topology.map { it.toUiChatMessageStub() } }
        val pathIds = MutableStateFlow<Set<String>>(emptySet())
        launch {
            combine(stubs, selectedChildren, state.generationSnapshot) { all, selected, snapshot ->
                val path = withContext(projectionDispatcher) {
                    ConversationUiState.resolvePath(all, snapshot.streamingMessage, selected)
                }
                WebSyncEvent.Path(
                    conversationId = id,
                    messages = path.map { it.toWebPathEntry() },
                    generating = snapshot.isGenerating,
                )
            }
                .distinctUntilChanged()
                .collect { event ->
                    pathIds.value = event.messages.mapTo(mutableSetOf()) { it.id }
                    outbound.send(event)
                }
        }
        launch {
            state.generationSnapshot
                .map { it.streamingMessage }
                .distinctUntilChanged()
                .combine(display) { message, context -> message to context }
                .mapLatest { (message, context) ->
                    message?.let { project(it, isStreaming = true, context) }
                }
                .collect { outbound.send(WebSyncEvent.Streaming(id, it)) }
        }
        launch { servePayloads(id, watched, pathIds, display, outbound) }
    }

    /** One payload subscription per watched row; only rows on the current path are served. */
    private suspend fun servePayloads(
        conversationId: String,
        watched: StateFlow<Set<String>>,
        pathIds: StateFlow<Set<String>>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
    ) = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        combine(watched, pathIds) { ids, path -> ids intersect path }
            .distinctUntilChanged()
            .collect { ids ->
                (jobs.keys - ids).forEach { jobs.remove(it)?.cancel() }
                (ids - jobs.keys).forEach { messageId ->
                    jobs[messageId] = launch {
                        hydration.observeMessage(messageId) { it.forDisplay(customProviders.value) }
                            .distinctUntilChanged()
                            .combine(display) { message, context -> message to context }
                            .collect { (message, context) ->
                                if (message != null) {
                                    outbound.send(
                                        WebSyncEvent.Payload(
                                            conversationId,
                                            project(message, isStreaming = false, context),
                                        ),
                                    )
                                }
                            }
                    }
                }
            }
    }

    private suspend fun project(
        message: ChatMessage,
        isStreaming: Boolean,
        display: WebDisplayContext,
    ): WebMessage = withContext(projectionDispatcher) {
        val shown = message.forDisplay(customProviders.value)
        shown.toWeb(webPresentation(shown, isStreaming, display))
    }

    companion object {
        private const val TAG = "WebUiSync"
        const val COMMAND_OPEN = "open"
        const val COMMAND_WATCH = "watch"
        const val COMMAND_SEND = "send"
        const val COMMAND_STOP = "stop"
        const val COMMAND_DRAFT = "draft"
        const val COMMAND_CANCEL_WAITING = "cancel_waiting"
        const val COMMAND_MODEL = "model"
        const val COMMAND_REMOVE_QUEUED = "remove_queued"
        const val COMMAND_SEND_QUEUED = "send_queued"
        const val DRAWER_PAGE_SIZE = 80

        /** Upper bound on rows one browser may subscribe to at a time. */
        const val MAX_WATCHED = 48

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        /** Same decoding as the app's assembler: the key `"null"` is the root. */
        internal fun decodeSelectedChildren(raw: String?): Map<String?, String> =
            raw?.let {
                runCatching {
                    json.decodeFromString<Map<String, String>>(it)
                        .mapKeys { (key, _) -> if (key == "null") null else key }
                }.getOrNull()
            }.orEmpty()
    }
}

/** A math span in [WebText.markdown] is `MATH_OPEN + index + MATH_CLOSE`. */
internal const val MATH_OPEN = '\uE000'
internal const val MATH_CLOSE = '\uE001'

/**
 * Markdown with math already split out by the app's [parseLatexSpans], so the browser detects
 * formulas exactly as the app does. Private-use placeholder characters already in the text are
 * replaced so they cannot be mistaken for a placeholder.
 */
internal fun String.toWebText(parseInlineDollarMath: Boolean): WebText {
    val spans = parseLatexSpans(this, parseInlineDollarMath)
    val math = mutableListOf<WebMath>()
    val sourceMap = mutableListOf<List<Int>>()
    var sourceCursor = 0
    var lastDelta = 0
    val markdown = buildString {
        fun mapped(character: Char, original: Int) {
            val delta = original - length
            if (delta != lastDelta) {
                sourceMap += listOf(length, original)
                lastDelta = delta
            }
            append(character)
        }
        spans.forEach { span ->
            if (span.isLatex) {
                val start = this@toWebText.indexOf(span.source, sourceCursor)
                check(start >= sourceCursor)
                val slot = "$MATH_OPEN${math.size}$MATH_CLOSE"
                slot.forEach { mapped(it, start) }
                sourceCursor = start + span.source.length
                math += WebMath(span.content, span.display)
            } else {
                span.content.forEachIndexed { index, character ->
                    // Plain-span dollar escaping can insert a protective slash, but no source glyph.
                    val inserted = character == '\\' && span.content.getOrNull(index + 1) == '$' &&
                        this@toWebText.getOrNull(sourceCursor) == '$'
                    mapped(if (character == MATH_OPEN || character == MATH_CLOSE) '\uFFFD' else character, sourceCursor)
                    if (!inserted) sourceCursor += 1
                }
            }
        }
        if (sourceCursor - length != lastDelta) sourceMap += listOf(length, sourceCursor)
    }
    return WebText(markdown, math, sourceMap)
}
