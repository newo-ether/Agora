package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ThinkingResolution
import com.newoether.agora.data.thinkingCapabilityForSelectedModel
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.util.Constants
import com.newoether.agora.ui.components.parseLatexSpans
import com.newoether.agora.ui.chat.scanConversationSearchMatches
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    private val forkShare: com.newoether.agora.viewmodel.ConversationForkShareController,
    private val askUser: com.newoether.agora.viewmodel.AskUserController,
    private val shellConfirmation: com.newoether.agora.viewmodel.ShellConfirmationController,
    /** Builds this connection's own context accounting; see [WebUiContextAccounting]. */
    private val contextAccounting: WebUiContextAccounting,
    private val search: suspend (String) -> List<Pair<MessageEntity, Float>>,
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
            val listLimit = MutableStateFlow(DRAWER_PAGE_SIZE)
            val list = combine(
                listLimit.flatMapLatest { limit ->
                    conversations.observeDrawerConversations(limit + 1).map { limit to it }
                },
                registry.activeConversationIds,
            ) { (limit, items), active ->
                WebSyncEvent.Conversations(items.take(limit).map { it.toWeb(it.id in active) }, items.size > limit, limit)
            }
            val displayContext = display.distinctUntilChanged()
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { displayContext.collect { outbound.send(it.toEvent()) } }
            launch {
                list.distinctUntilChanged().collect { outbound.send(it) }
            }
            val watched = MutableStateFlow<Set<String>>(emptySet())
            val session = openChatSession(this)
            val contextNewChatPrompt = MutableStateFlow<String?>(null)
            val contextNewChatSettings = MutableStateFlow<ConversationSettings?>(null)
            val contextProjector = contextAccounting.open({ contextNewChatPrompt.value }, { contextNewChatSettings.value })
            val contextRequest = MutableStateFlow<WebContextAccountingRequest?>(null)
            val contextRevision = MutableStateFlow(0L)
            var contextSettled: String? = null
            val connectionId = java.util.UUID.randomUUID().toString()
            val connectionJob = coroutineContext[Job]!!
            val pendingPageAction = java.util.concurrent.atomic.AtomicReference<WebSyncCommand?>(null)
            var lastPageActionId = 0L
            val interactionActionId = MutableStateFlow(0L)
            val searchRevision = MutableStateFlow(0L)
            var searchJob: Job? = null
            val conversationQuery = MutableStateFlow<WebSyncCommand?>(null)
            // Row commands resolve inside the rows this connection is showing, not the phone's own view.
            val visiblePath = MutableStateFlow<List<ChatMessage>>(emptyList())
            try {
                session.start()
                connections[connectionId] = login to session
                outbound.send(WebSyncEvent.Connection(connectionId))
                // Each connection projects on its own, so a browser prices the conversation it shows
                // and never the phone's figure or another browser's. A request is raised by the
                // composer (model, window, New Chat workspace) and by this connection's own path.
                launch {
                    combine(contextRequest, contextRevision) { request, revision -> request to revision }
                        .filter { it.first != null }.distinctUntilChanged()
                        .collect { (request, _) ->
                            request?.let {
                                contextProjector.request(this, it.conversationId, null, it.selectedModelId, it.tokenBudget)
                            }
                        }
                }
                launch {
                    combine(
                        contextRequest, contextProjector.projection,
                        contextAccounting.compactThresholdPercent, contextAccounting.compactEnabled,
                    ) { request, projection, threshold, enabled ->
                        request?.let {
                            val event = contextAccountingEvent(it, projection, contextSettled, threshold, enabled)
                            if (projection.completed && projection.conversationId == it.conversationId) {
                                contextSettled = if (projection.failed) null else it.conversationId
                            }
                            event
                        }
                    }.filterNotNull().distinctUntilChanged().collect { outbound.send(it) }
                }
                launch {
                    combine(session.openTarget, askUser.requests, shellConfirmation.pendingShellCommand, interactionActionId) {
                        target, questions, shell, actionId ->
                        val visible = com.newoether.agora.ui.chat.interaction.userInteractions(target.conversationId, questions, shell)
                        WebSyncEvent.Interactions(target.conversationId, target.browserSeq, actionId,
                            visible.flatMap { request -> when (request) {
                                is com.newoether.agora.ui.chat.interaction.UserInteraction.Question -> request.requests.map { question ->
                                    buildJsonObject {
                                        put("kind", "question")
                                        put("id", question.id.toString())
                                        put("question", question.question)
                                        put("options", JsonArray(question.options.map(::JsonPrimitive)))
                                        put("allowMultiple", question.allowMultiple)
                                        put("blocking", question.blocking)
                                    }
                                }
                                is com.newoether.agora.ui.chat.interaction.UserInteraction.ShellCommand -> listOf(buildJsonObject {
                                    put("kind", "shell")
                                    put("id", request.pending.id.toString())
                                    put("server", request.pending.server)
                                    put("summary", request.pending.summary)
                                })
                            } })
                    }.distinctUntilChanged().collect { outbound.send(it) }
                }
                launch { session.snackbars.collect { outbound.send(WebSyncEvent.Snackbar(it)) } }
                launch { session.scrollRequests.collect { outbound.send(it) } }
                // The session decides what is open; a runtime move (New Chat send, deletion) is
                // announced before any event of the new target so the browser can follow it.
                launch {
                    session.openTarget.collectLatest { target ->
                        conversationQuery.value = null
                        if (target.movedByServer) {
                            outbound.send(WebSyncEvent.Opened(target.conversationId, target.browserSeq))
                        }
                        coroutineScope {
                            launch {
                                session.composerState
                                    .combine(customProviders) { state, _ -> state }
                                    .filter { it.conversationId == target.conversationId && it.seq == target.browserSeq }
                                    // The composer frame this connection just showed is also what its projector
                                    // has to follow: the model and window set the request, and the New Chat
                                    // workspace it carries belongs to this connection alone.
                                    .onEach { state ->
                                        contextNewChatPrompt.value = state.systemPromptId
                                        contextNewChatSettings.value = state.generationParameters
                                            .takeUnless { settings -> settings.isAllNull() }
                                        state.controls?.let { controls ->
                                            contextRequest.value = WebContextAccountingRequest(
                                                state.conversationId, state.seq, state.modelId, controls.contextWindow,
                                                state.systemPromptId,
                                            )
                                        }
                                    }
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
                                            buildJsonObject {
                                                put("selectedId", it.systemPromptId)
                                                put("activeId", it.activeSystemPromptId)
                                                put("items", JsonArray(it.systemPrompts.map { prompt -> buildJsonObject {
                                                    put("id", prompt.id)
                                                    put("title", prompt.title)
                                                } }))
                                            },
                                        )
                                    }.distinctUntilChanged().collect { outbound.send(it) }
                            }
                            target.conversationId?.let { id ->
                                openConversation(id, session, watched, contextRevision, displayContext, outbound, connectionId, target.browserSeq, conversationQuery, visiblePath)
                            }
                        }
                    }
                }
                for (text in incoming) {
                    val command = runCatching {
                        json.decodeFromString(WebSyncCommand.serializer(), text)
                    }.getOrNull() ?: continue
                    when (command.type) {
                        "conversation_search" -> {
                            val target = session.openTarget.value
                            if (command.connectionId != connectionId || command.conversationId == null ||
                                command.conversationId != target.conversationId || command.seq != target.browserSeq ||
                                command.revision <= (conversationQuery.value?.revision ?: 0L)) continue
                            conversationQuery.value = command
                        }
                        "search" -> {
                            if (command.connectionId != connectionId || command.revision <= searchRevision.value) continue
                            searchRevision.value = command.revision
                            searchJob?.cancel()
                            val query = command.text.orEmpty()
                            searchJob = launch(projectionDispatcher) {
                                if (query.isBlank()) {
                                    outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false))
                                    return@launch
                                }
                                outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, true))
                                try {
                                    delay(200)
                                    val matches = search(query).take(20)
                                    currentCoroutineContext().ensureActive()
                                    val titles = conversations.getDrawerConversations(matches.map { it.first.conversationId }.distinct())
                                        .associate { it.id to it.title }
                                    val providers = customProviders.value
                                    val items = matches.groupBy { it.first.conversationId }.mapNotNull { (id, entries) ->
                                        val title = titles[id] ?: return@mapNotNull null
                                        buildJsonObject {
                                            put("id", id)
                                            put("title", replaceCustomProviderIdsForDisplay(title, providers))
                                            put("score", entries.maxOf { it.second })
                                            put("snippets", JsonArray(entries.take(2).map { (message, _) ->
                                                val text = replaceCustomProviderIdsForDisplay(message.text, providers)
                                                val index = text.indexOf(query, ignoreCase = true)
                                                val start = if (index < 0) 0 else (index - 20).coerceAtLeast(0)
                                                val end = if (index < 0) text.length else (index + query.length + 20).coerceAtMost(text.length)
                                                buildJsonObject {
                                                    put("role", message.participant.name)
                                                    put("text", (if (start > 0) "…" else "") + text.substring(start, end) +
                                                        (if (end < text.length) "…" else ""))
                                                }
                                            }))
                                        }
                                    }
                                    if (searchRevision.value == command.revision) {
                                        outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false, items))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (searchRevision.value == command.revision) {
                                        outbound.send(WebSyncEvent.Search(connectionId, command.revision, query, false, failed = true))
                                    }
                                }
                            }
                        }
                        "question_submit", "question_skip", "shell_decision" -> {
                            val target = session.openTarget.value
                            if (command.connectionId != connectionId || command.seq != target.browserSeq ||
                                command.conversationId != target.conversationId || command.actionId <= interactionActionId.value) continue
                            // Consume identified actions even if the request disappeared on another client.
                            interactionActionId.value = command.actionId
                            val visibleQuestions = askUser.requests.value.filter { it.conversationId == null || it.conversationId == target.conversationId }
                            when (command.type) {
                                "question_submit" -> {
                                    val answers = command.answers ?: continue
                                    if (answers.isEmpty()) continue
                                    val waiting = visibleQuestions.associateBy { it.id.toString() }
                                    if (answers.any { (id, answer) ->
                                        val request = waiting[id]
                                        request == null || answer.choices.distinct().size != answer.choices.size ||
                                            answer.choices.any { it !in request.options } || (!request.allowMultiple && answer.choices.size > 1)
                                    }) continue
                                    askUser.submitAll(answers.map { (id, answer) -> id.toLong() to
                                        com.newoether.agora.viewmodel.AskUserController.Answer(answer.choices, answer.text, answer.answered) })
                                }
                                "question_skip" -> {
                                    val request = visibleQuestions.firstOrNull { it.id.toString() == command.requestId } ?: continue
                                    askUser.dismiss(request.id)
                                }
                                "shell_decision" -> {
                                    val pending = shellConfirmation.pendingShellCommand.value ?: continue
                                    if (pending.id.toString() != command.requestId ||
                                        (pending.conversationId != null && pending.conversationId != target.conversationId)) continue
                                    shellConfirmation.resolve(pending.id, command.enabled ?: continue, command.alwaysAllow == true)
                                }
                            }
                        }
                        "list_more" -> if (command.tokens == listLimit.value) listLimit.value += DRAWER_PAGE_SIZE
                        "pin" -> {
                            val id = command.conversationId ?: continue
                            val pinned = command.enabled ?: continue
                            conversations.setConversationPinned(id, pinned)
                        }
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
                        "edit", "regenerate", "delete" -> session.rowCommand(command, visiblePath.value)
                        "attachment_remove", "attachment_retry", "attachment_pdf", "attachment_video" ->
                            session.attachmentCommand(command)
                        "setting" -> session.settingCommand(command)
                        "advanced", "compact", "system_prompt" -> session.editorCommand(command)
                        "fork", "share" -> {
                            val target = session.openTarget.value
                            if (target.conversationId == null || target.conversationId != command.conversationId ||
                                target.browserSeq != command.seq || command.actionId <= lastPageActionId) continue
                            val pending = pendingPageAction.get()
                            if (pending != null && pending.seq == target.browserSeq && pending.conversationId == target.conversationId) continue
                            lastPageActionId = command.actionId
                            pendingPageAction.set(command)
                            val isCurrent = { connectionJob.isActive && session.openTarget.value == target }
                            val complete: (Boolean, String?) -> Unit = { success, shared ->
                                if (pendingPageAction.compareAndSet(command, null) && connectionJob.isActive) launch {
                                    outbound.send(WebSyncEvent.PageAction(command.conversationId!!, command.seq,
                                        command.actionId, command.type, success, shared))
                                }
                            }
                            // A row's Fork and Share name their own message; the top bar sends neither,
                            // so it keeps forking and sharing the whole conversation.
                            val started = when {
                                command.type == "fork" -> forkShare.fork(session, command.messageId,
                                    isCurrent = isCurrent, openFork = { session.openForkIfCurrent(it, target) },
                                    onResult = { complete(it, null) })
                                command.messageId != null -> forkShare.shareGeneration(session,
                                    command.messageId, isCurrent) { complete(it != null, it) }
                                else -> forkShare.shareConversation(session, isCurrent) { complete(it != null, it) }
                            }
                            if (!started) complete(false, null)
                        }
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
        session: WebUiChatSession,
        watched: StateFlow<Set<String>>,
        contextRevision: MutableStateFlow<Long>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
        connectionId: String,
        seq: Long,
        query: StateFlow<WebSyncCommand?>,
        visiblePath: MutableStateFlow<List<ChatMessage>>,
    ) {
        val selected = conversations.observeConversation(id)
        val row = selected.first()
        if (row == null || row.taskId != null) {
            outbound.send(WebSyncEvent.Deleted(id))
            return
        }
        // A failure in any collector ends this conversation only, never the connection.
        try {
            coroutineScope { observeConversation(session, id, selected, visiblePath, watched, contextRevision, display, outbound, connectionId, seq, query) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e(TAG, "WebUI failed to load a conversation", error)
            outbound.send(WebSyncEvent.LoadFailed(id))
        }
    }

    private suspend fun CoroutineScope.observeConversation(
        session: WebUiChatSession,
        id: String,
        selected: Flow<ChatConversation?>,
        visiblePath: MutableStateFlow<List<ChatMessage>>,
        watched: StateFlow<Set<String>>,
        contextRevision: MutableStateFlow<Long>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
        connectionId: String,
        seq: Long,
        query: StateFlow<WebSyncCommand?>,
    ) {
        executionCoordinator.tryWithConversationLock(id) {
            conversations.recoverConversationRuntime(id)
        }
        val state = registry.getOrCreate(id)
        val selectedChildren = selected.map { row ->
            if (row == null) {
                outbound.send(WebSyncEvent.Deleted(id))
                this@observeConversation.cancel()
            }
            decodeSelectedChildren(row?.selectedBranchesJson)
        }.distinctUntilChanged()
        val payloadRevision = MutableStateFlow(0L)
        val stubs = conversations.observeMessageTopology(id)
            .map { topology ->
                payloadRevision.value += 1
                topology.map { it.toUiChatMessageStub().copy(tokenCount = 0) }
            }.distinctUntilChanged()
        // Text and timing are not branch inputs. Deduplicate before walking the graph.
        val pathSnapshots = state.generationSnapshot.map { snapshot ->
            snapshot.copy(streamingMessage = snapshot.streamingMessage?.let { message ->
                ChatMessage(id = message.id, parentId = message.parentId, text = "",
                    participant = message.participant, status = message.status, timestamp = message.timestamp,
                    modelName = message.modelName, runId = message.runId, runSequence = message.runSequence,
                    consumedAtPass = message.consumedAtPass)
            })
        }.distinctUntilChanged()
        val liveMessageId = state.generationSnapshot.map { snapshot ->
            snapshot.streamingMessage?.takeUnless { it.status in
                setOf(MessageStatus.SUCCESS, MessageStatus.STOPPED, MessageStatus.ERROR) }?.id
        }.distinctUntilChanged()
        val pathIds = MutableStateFlow<Set<String>>(emptySet())
        val searchIds = MutableStateFlow<List<String>>(emptyList())
        launch {
            combine(stubs, selectedChildren, pathSnapshots) { all, selected, snapshot ->
                val path = withContext(projectionDispatcher) {
                    ConversationUiState.resolvePath(all, snapshot.streamingMessage, selected)
                }
                // Row commands resolve the phone's generation boundary inside the path this browser shows.
                visiblePath.value = path
                WebSyncEvent.Path(
                    conversationId = id,
                    messages = path.map { it.toWebPathEntry() },
                    generating = snapshot.isGenerating,
                )
            }
                .distinctUntilChanged()
                .collect { event ->
                    // The row set and the generating flag are what move the priced context, so this
                    // connection re-prices here instead of on every streamed character.
                    contextRevision.value += 1
                    pathIds.value = event.messages.mapTo(mutableSetOf()) { it.id }
                    searchIds.value = event.messages.filter {
                        (it.participant == "USER" || it.participant == "MODEL") &&
                            !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                            !it.id.startsWith(Constants.COMPACT_MSG_PREFIX)
                    }.map { it.id }
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
        launch { servePayloads(id, watched, pathIds, liveMessageId, display, outbound) }
        launch(projectionDispatcher) {
            combine(searchIds, query, customProviders, payloadRevision, state.generationSnapshot) { ids, command, providers, _, snapshot ->
                Triple(ids, command, providers) to snapshot.streamingMessage
            }.collectLatest { (input, streaming) ->
                    val (ids, command, providers) = input
                    if (command == null || command.conversationId != id || command.seq != seq) return@collectLatest
                    val text = command.text.orEmpty()
                    suspend fun publish(searching: Boolean, matches: List<JsonObject> = emptyList(), failed: Boolean = false) {
                        outbound.send(WebSyncEvent.ConversationSearch(connectionId, id, seq, command.revision, text, searching, matches, failed))
                    }
                    if (text.isBlank()) { publish(false); return@collectLatest }
                    publish(true)
                    try {
                        val matches = scanConversationSearchMatches(ids, text) { page ->
                            val rows = hydration.loadMessages(id, page) { it.forDisplay(providers) }
                            if (streaming == null || streaming.id !in page) rows
                            else rows.filterNot { it.id == streaming.id } + streaming.forDisplay(providers)
                        }.map { match -> buildJsonObject {
                            put("messageId", match.messageId)
                            put("start", match.start)
                            put("endExclusive", match.endExclusive)
                            put("occurrence", match.occurrenceInMessage)
                            put("key", match.key)
                        } }
                        currentCoroutineContext().ensureActive()
                        publish(false, matches)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DebugLog.e(TAG, "WebUI conversation search failed", error)
                        publish(false, failed = true)
                    }
                }
        }
    }

    /** One payload subscription per watched row; only rows on the current path are served. */
    private suspend fun servePayloads(
        conversationId: String,
        watched: StateFlow<Set<String>>,
        pathIds: StateFlow<Set<String>>,
        liveMessageId: Flow<String?>,
        display: Flow<WebDisplayContext>,
        outbound: SendChannel<WebSyncEvent>,
    ) = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        combine(watched, pathIds, liveMessageId) { ids, path, live -> (ids intersect path) - setOfNotNull(live) }
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
