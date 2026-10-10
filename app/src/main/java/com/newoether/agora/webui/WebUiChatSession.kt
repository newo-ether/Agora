package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.updateConversationModel
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.thinkingCapabilityForSelectedModel
import com.newoether.agora.ui.chat.EffectiveConversationControls
import com.newoether.agora.ui.chat.resolveEffectiveConversationControls
import com.newoether.agora.ui.chat.withGenerationParameters
import com.newoether.agora.ui.chat.validGenerationParameters
import com.newoether.agora.viewmodel.CompactRequest
import com.newoether.agora.viewmodel.CompactResult
import com.newoether.agora.viewmodel.CompactFailureReason
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ThinkingResolution
import com.newoether.agora.model.apiModelName
import com.newoether.agora.viewmodel.CurrentConversationRuntimeFacade
import com.newoether.agora.viewmodel.QueuedSend
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator
import com.newoether.agora.viewmodel.ChatClient
import com.newoether.agora.viewmodel.ChatClients
import com.newoether.agora.viewmodel.ComposerDraftController
import com.newoether.agora.viewmodel.ComposerDraftPersistence
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import com.newoether.agora.viewmodel.ConversationRenderStore
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationUiStateAssembler
import com.newoether.agora.viewmodel.ConversationWorkspaceDraft
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import com.newoether.agora.viewmodel.NewChatWorkspaceSnapshot
import com.newoether.agora.viewmodel.resolveValidModel
import com.newoether.agora.viewmodel.validChatModels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.FileValidator
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import com.newoether.agora.data.repository.decodeSelectedAttachments
import com.newoether.agora.data.repository.removedReclaimablePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One signed-in browser connection as a [ChatClient] of the process-scoped chat runtime.
 *
 * Send, Stop and queueing go through the same runtime owners the phone uses, so they work while
 * the app is in the background. The browser's open conversation, composer draft and New Chat
 * workspace belong to this session only (application-ui.md section 36): drafts stay in memory
 * and a New Chat send never touches the phone's persisted New Chat workspace. An existing
 * conversation's model is read from the conversation itself, which the phone shares.
 *
 * The session lives exactly as long as its sync connection: [start] attaches it, [close]
 * detaches it and releases the retained composer owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WebUiChatSession(
    private val generation: MessageGenerationController,
    private val generationStop: GenerationStopAdapter,
    private val clients: ChatClients,
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    executionCoordinator: ConversationExecutionCoordinator,
    private val settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    attachmentProcessor: AttachmentImportProcessor,
    private val scope: CoroutineScope,
    private val uploadDirectory: File,
    private val compactFailureMessage: (CompactResult.Failed) -> String,
    private val allowLocalSandbox: () -> Boolean = { false },
    sandboxHomeDir: () -> File? = { null },
) : ChatClient {
    /** Where this session shows; [browserSeq] is the browser's last open request it follows. */
    data class OpenTarget(
        val conversationId: String?,
        val browserSeq: Long,
        /** True when the runtime, not the browser, moved the session (for example New Chat send). */
        val movedByServer: Boolean,
    )

    /** The composer owner the browser shows and that owner's submission phase. */
    data class ComposerState(
        val conversationId: String?,
        val snapshot: ConversationComposerSubmissionSnapshot,
        val seq: Long = 0L,
        val text: String = "",
        val editRevision: Long = 0L,
        val actionId: Long = 0L,
        val modelValid: Boolean = false,
        val generating: Boolean = false,
        val stopping: Boolean = false,
        val modelId: String = "",
        val models: Map<String, String> = emptyMap(),
        val queue: List<QueuedSend> = emptyList(),
        val attachments: List<SelectedAttachment> = emptyList(),
        val pdfProgress: Map<String, Pair<Int, Int>> = emptyMap(),
        val controls: EffectiveConversationControls? = null,
        val generationParameters: ConversationSettings = ConversationSettings(),
        val generationDefaults: ConversationSettings = ConversationSettings(),
        val compactDefaults: CompactRequest? = null,
        val compacting: Boolean = false,
    )

    private val target = MutableStateFlow(OpenTarget(null, browserSeq = 0L, movedByServer = false))
    val openTarget: StateFlow<OpenTarget> = target.asStateFlow()
    // Updated together with [target] so a send right after an open captures the new target.
    private val openId = MutableStateFlow<String?>(null)

    private val newChatEntryId = AtomicLong(0L)
    private val _snackbars = MutableSharedFlow<String>(extraBufferCapacity = SNACKBAR_BUFFER)
    val snackbars: SharedFlow<String> = _snackbars.asSharedFlow()
    private val _scrollRequests = MutableSharedFlow<WebSyncEvent.ScrollToBottom>(extraBufferCapacity = 8)
    val scrollRequests: SharedFlow<WebSyncEvent.ScrollToBottom> = _scrollRequests.asSharedFlow()

    // The canonical render owner keeps this client's store equal to what the phone's store holds
    // for the same conversation; Stop snapshots in-flight rows from any showing client's store.
    private val ui = ConversationUiStateAssembler(
        conversations = conversations,
        registry = registry,
        executionCoordinator = executionCoordinator,
        currentConversationId = openId,
        scope = scope,
    )
    private val draftPersistence = SessionDraftPersistence(conversations)
    private val drafts = ComposerDraftController(
        persistence = draftPersistence,
        conversations = conversations,
    )
    private val composers = ConversationComposerController(
        scope = scope,
        drafts = drafts,
        processor = attachmentProcessor,
        sandboxHomeDir = sandboxHomeDir,
    )

    /** Model chosen on this browser's New Chat page; null follows the default model. */
    private val newChatModelId = MutableStateFlow<String?>(null)
    private val newChatSettings = MutableStateFlow<ConversationSettings?>(null)
    private val validModels = settings.validChatModels(scope)
    private val runtimeFacade = CurrentConversationRuntimeFacade(openId, registry, scope)
    private val modelLabels = combine(
        validModels, settings.modelAliases, settings.customProviders, settings.modelProviderNames,
    ) { valid, aliases, providers, showProvider ->
        valid.orEmpty().sortedWith(compareBy(
            { providerDisplayName(ModelId.parse(it).providerName, providers).lowercase() },
            { ModelId.parse(it).apiModelName.lowercase() },
        )).associateWith { modelDisplayName(it, aliases, providers, showProvider[it] != false) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val activeModel: StateFlow<Pair<String?, String>?> = openId.flatMapLatest { id ->
        combine(
            if (id == null) newChatModelId else conversations.observeConversation(id).map { it?.modelId },
            settings.selectedModel,
            validModels,
        ) { referenced, fallback, valid ->
            id to if (valid == null) "" else resolveValidModel(referenced, fallback, valid)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private val controlChanges = combine(listOf(
        settings.conversationSettings, newChatSettings, settings.codeExecutionEnabled, settings.googleSearchEnabled,
        settings.thinkingEnabled, settings.thinkingLevel, settings.thinkingBudgetEnabled, settings.thinkingBudgetTokens,
        settings.openAiServiceTierEnabled, settings.openAiServiceTier, settings.openAiResponsesApiEnabled,
        settings.webSearchEnabled, settings.shellEnabled, settings.localLowContextModeEnabled, settings.maxContextWindow,
        settings.customProviders,
        settings.defaultTemperature, settings.defaultMaxTokens, settings.defaultTopP,
        settings.defaultFrequencyPenalty, settings.defaultPresencePenalty,
        settings.contextCompactModel, settings.contextCompactPrompt, settings.contextCompactRetainCount,
    )) { Unit }
    private fun selectedProvider(id: String?): String {
        val model = activeModel.value?.takeIf { it.first == id }?.second.orEmpty()
        val reference = ModelId.parse(model).providerName
        return providerDisplayName(reference, settings.customProviders.value)
    }
    private fun effectiveControls(id: String?): EffectiveConversationControls = resolveEffectiveConversationControls(
        id, if (id == null) newChatSettings.value else settings.conversationSettings.value[id],
        ConversationSettings(
            contextWindow = settings.maxContextWindow.value, codeExecutionEnabled = settings.codeExecutionEnabled.value,
            googleSearchEnabled = settings.googleSearchEnabled.value, thinkingEnabled = settings.thinkingEnabled.value,
            thinkingLevel = settings.thinkingLevel.value, thinkingBudgetEnabled = settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = settings.thinkingBudgetTokens.value, webSearchEnabled = settings.webSearchEnabled.value,
            shellEnabled = settings.shellEnabled.value, lowContextModeEnabled = settings.localLowContextModeEnabled.value,
            openAiServiceTierEnabled = settings.openAiServiceTierEnabled.value, openAiServiceTier = settings.openAiServiceTier.value,
        ),
        selectedProvider(id),
        settings.openAiResponsesApiEnabled.value, settings.customProviders.value,
    )

    private val submission = ConversationComposerSubmissionController(
        scope = scope,
        composers = composers,
        drafts = drafts,
        captureTarget = { ownerId ->
            val current = openId.value
            generation.captureForegroundSendTarget(
                ownerId = ownerId,
                currentId = current,
                isNewChatMode = current == null,
                newChatEntryId = newChatEntryId.get(),
                modelId = activeModel.value?.takeIf { it.first == current }?.second.orEmpty(),
                captureNewChatWorkspace = {
                    NewChatWorkspaceSnapshot(
                        persisted = null,
                        modelId = newChatModelId.value,
                        systemPromptId = null,
                        conversationSettings = newChatSettings.value,
                        sessionLocal = true,
                    )
                },
            )
        },
        prepare = { sendTarget, composer ->
            generation.prepareForegroundSend(sendTarget, composer, this@WebUiChatSession)
        },
        send = { admission, text, attachments, onAccepted ->
            generation.sendMessage(admission, text, attachments, { accepted ->
                if (admission.target.wasNewChat) ownerMutex.withLock {
                    if (newChatSettings.value == admission.newConversationSettings) newChatSettings.value = null
                }
                onAccepted(accepted)
            }, this@WebUiChatSession)
        },
        // The in-memory draft store cannot fail to clear, so no retry surface is needed.
    )

    private val ownerMutex = Mutex()
    private val editRevision = MutableStateFlow(0L)
    private val actionId = MutableStateFlow(0L)
    private var retainedOwner: String? = null
    private var closed = false
    private val uploadedSources = mutableListOf<SelectedAttachment>()
    private val ownerState = MutableStateFlow<Pair<String, StateFlow<ConversationComposerSubmissionSnapshot>>?>(null)
    val composerState: Flow<ComposerState> = combine(ownerState, target) { owner, selected ->
        owner?.takeIf { it.first == (selected.conversationId ?: NEW_CHAT_WORKSPACE_ID) }?.let { it to selected }
    }.filterNotNull().flatMapLatest { (owned, _) ->
        val (owner, state) = owned
        val draft = ownerMutex.withLock {
            if (retainedOwner == owner) composers.state(owner) else null
        } ?: return@flatMapLatest flowOf()
        val runtime = owner.takeUnless { it == NEW_CHAT_WORKSPACE_ID }?.let(registry::getOrCreate)
        combine(draft, state, editRevision, actionId, activeModel) { _, _, _, _, _ -> Unit }
            .combine(modelLabels) { _, _ -> Unit }
            .combine(controlChanges) { _, _ -> Unit }
            .combine(runtime?.queuedSends ?: flowOf(emptyList())) { _, _ -> Unit }
            .combine(runtime?.generating ?: flowOf(false)) { _, _ -> Unit }
            .combine(runtime?.stopping ?: flowOf(false)) { _, _ ->
                Unit
            }.combine(runtime?.streamingMessage?.map { it?.isContextCompact() == true }?.distinctUntilChanged() ?: flowOf(false)) { _, compacting ->
                ownerMutex.withLock {
                    val selected = target.value
                    if (retainedOwner != owner || owner != (selected.conversationId ?: NEW_CHAT_WORKSPACE_ID)) {
                        null
                    } else {
                        ComposerState(
                            selected.conversationId, state.value, selected.browserSeq, draft.value.text,
                            editRevision.value, actionId.value,
                            activeModel.value?.let { it.first == selected.conversationId && it.second.isNotBlank() } == true,
                            runtime?.generating?.value == true, runtime?.stopping?.value == true,
                            activeModel.value?.takeIf { it.first == selected.conversationId }?.second.orEmpty(),
                            modelLabels.value, runtime?.queuedSends?.value.orEmpty().sortedBy(QueuedSend::createdAt),
                            draft.value.attachments,
                            draft.value.pdfPreviewProgress,
                            effectiveControls(selected.conversationId),
                            (if (selected.conversationId == null) newChatSettings.value else settings.conversationSettings.value[selected.conversationId]) ?: ConversationSettings(),
                            ConversationSettings(contextWindow = settings.maxContextWindow.value,
                                temperature = settings.defaultTemperature.value, maxTokens = settings.defaultMaxTokens.value,
                                topP = settings.defaultTopP.value, frequencyPenalty = settings.defaultFrequencyPenalty.value,
                                presencePenalty = settings.defaultPresencePenalty.value),
                            CompactRequest(settings.contextCompactModel.value ?: activeModel.value?.second.orEmpty(),
                                settings.contextCompactPrompt.value, settings.contextCompactRetainCount.value),
                            compacting,
                        )
                    }
                }
            }.filterNotNull()
    }

    /** Attaches to the runtime and admits the initial New Chat composer. */
    suspend fun start() {
        settings.awaitInitialLoad()
        clients.attach(this)
        ui.start()
        ownerMutex.withLock { retainOwnerLocked(NEW_CHAT_WORKSPACE_ID) }
    }

    /** The browser opened [conversationId] (null is a fresh New Chat page) with request [seq]. */
    suspend fun open(conversationId: String?, seq: Long) = ownerMutex.withLock {
        if (conversationId == null) {
            newChatEntryId.incrementAndGet()
        }
        editRevision.value = 0L
        actionId.value = 0L
        target.value = OpenTarget(conversationId, seq, movedByServer = false)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    /** Applies one ordered browser edit to the canonical composer owner. */
    suspend fun edit(text: String, revision: Long, seq: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq || revision <= editRevision.value) return@withLock
        val owner = retainedOwner ?: return@withLock
        composers.updateText(owner, text)
        composers.persistText(owner, text)
        editRevision.value = revision
    }

    suspend fun send(text: String, seq: Long = target.value.browserSeq, commandId: Long = 0L) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        val owner = retainedOwner ?: return@withLock
        composers.updateText(owner, text)
        submission.submit(owner, text, composers.state(owner).value.attachments.map { it.localId })
        actionId.value = commandId
    }

    suspend fun cancelWaiting(seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        retainedOwner?.let(submission::cancelWaiting)
        actionId.value = commandId
    }

    suspend fun selectModel(modelId: String, seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        try {
            if (modelId !in validModels.value.orEmpty()) return@withLock
            val id = openId.value
            if (id == null) newChatModelId.value = modelId
            else if (!conversations.updateConversationModel(id, modelId)) return@withLock
            activeModel.first { resolved ->
                resolved != null && resolved.first == id &&
                    (resolved.second == modelId || modelId !in validModels.value.orEmpty())
            }
        } finally {
            actionId.value = commandId
        }
    }

    suspend fun removeQueued(id: String, seq: Long) = ownerMutex.withLock {
        if (seq == target.value.browserSeq) runtimeFacade.removeQueuedSend(id)
    }

    suspend fun sendQueued(seq: Long, commandId: Long) = ownerMutex.withLock {
        if (seq != target.value.browserSeq) return@withLock
        if (activeModel.value?.let { it.first == openId.value && it.second.isNotBlank() } == true) {
            runtimeFacade.requestQueueDrain()
        }
        actionId.value = commandId
    }

    fun stop(seq: Long = target.value.browserSeq) {
        if (seq == target.value.browserSeq) generationStop.stop(openId.value, this)
    }
    suspend fun editorCommand(command: WebSyncCommand) {
        val captured = ownerMutex.withLock {
            if (closed || retainedOwner == null || command.seq != target.value.browserSeq || command.conversationId != openId.value) return@withLock null
            if (command.type == "advanced") {
                try {
                    val draft = command.parameters ?: return@withLock null
                    if (!validGenerationParameters(draft)) return@withLock null
                    val id = openId.value
                    if (id == null) newChatSettings.value = (newChatSettings.value ?: ConversationSettings()).withGenerationParameters(draft)
                    else settings.updateConversationSettings(id) { it.withGenerationParameters(draft) }
                } finally { actionId.value = command.actionId }
                return@withLock null
            }
            if (command.type != "compact") return@withLock null
            val id = openId.value
            val request = command.retainCount?.let { CompactRequest(command.modelId.orEmpty(), command.text.orEmpty(), it,
                preserveSystemPrompt = settings.contextCompactPreserveSystemPrompt.value) }
            if (id == null || request == null || request.model !in validModels.value.orEmpty() || request.prompt.isBlank() ||
                request.retainLogicalMessages < 0 || registry.getOrCreate(id).stopping.value ||
                registry.getOrCreate(id).streamingMessage.value?.isContextCompact() == true) {
                actionId.value = command.actionId
                return@withLock null
            }
            actionId.value = command.actionId
            id to request
        } ?: return
        scope.launch {
            val result = try { generation.compactManual(captured.first, captured.second) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CompactResult.Failed(CompactFailureReason.GENERIC) }
            if (result is CompactResult.Failed) showSnackbar(compactFailureMessage(result))
        }
    }
    suspend fun settingCommand(command: WebSyncCommand) = ownerMutex.withLock {
        if (closed || command.seq != target.value.browserSeq) return@withLock
        try {
            if (retainedOwner == null) return@withLock
            val enabled = command.enabled
            val current = effectiveControls(openId.value)
            val model = activeModel.value?.takeIf { it.first == openId.value }?.second.orEmpty()
            if (command.modelId != null && command.modelId != model) return@withLock
            val capability = thinkingCapabilityForSelectedModel(model, settings.customProviders.value)
            val thinking = ThinkingResolution.resolve(capability, current.thinkingEnabled, current.thinkingLevel,
                current.thinkingBudgetEnabled, current.thinkingBudgetTokens)
            val modelId = ModelId.parse(model)
            val tiers = OpenAiServiceTiers.availableTiers(modelId.modelName,
                officialProvider = modelId.providerName == com.newoether.agora.util.Constants.PROVIDER_OPENAI)
            val allowed = when (command.setting) {
                "thinkingLevel" -> thinking.enabled && thinking.budgetTokens == null && capability.supportedEfforts.size > 1 && command.value in capability.supportedEfforts
                "thinkingBudgetEnabled" -> enabled != null && thinking.enabled && capability.supportsThinkingBudget
                "thinkingBudgetTokens" -> thinking.enabled && thinking.budgetTokens != null && command.tokens in ThinkingLevels.budgetPresets
                "openAiServiceTier" -> current.openAiServiceTierState.available && current.openAiServiceTierState.enabled && model.isNotBlank() && !current.lowContextModeEnabled && tiers.size > 1 && command.value in tiers
                "lowContextModeEnabled" -> current.showLowContextMode
                "thinkingEnabled" -> enabled == true || capability.canDisableThinking
                "codeExecutionEnabled", "googleSearchEnabled" -> selectedProvider(openId.value).equals("google", ignoreCase = true) && model.isNotBlank() && !current.lowContextModeEnabled
                "openAiWebSearchEnabled" -> current.openAiWebSearchAvailable && model.isNotBlank() && !current.lowContextModeEnabled
                "openAiServiceTierEnabled" -> current.openAiServiceTierState.available && model.isNotBlank() && !current.lowContextModeEnabled
                "webSearchEnabled" -> current.webSearchAvailable && !current.lowContextModeEnabled
                "shellEnabled" -> current.shellAvailable && !current.lowContextModeEnabled
                else -> false
            }
            if (!allowed || (command.setting?.endsWith("Enabled") == true && enabled == null)) return@withLock
            val update: (ConversationSettings) -> ConversationSettings = { previous ->
                when (command.setting) {
                    "thinkingLevel" -> previous.copy(thinkingLevel = command.value)
                    "thinkingBudgetEnabled" -> previous.copy(thinkingBudgetEnabled = enabled,
                        thinkingBudgetTokens = if (enabled == true && current.thinkingBudgetTokens < 1) ThinkingLevels.DefaultBudgetTokens else previous.thinkingBudgetTokens)
                    "thinkingBudgetTokens" -> previous.copy(thinkingBudgetTokens = command.tokens)
                    "openAiServiceTier" -> previous.copy(openAiServiceTier = command.value)
                    "lowContextModeEnabled" -> previous.copy(lowContextModeEnabled = enabled)
                    "thinkingEnabled" -> previous.copy(thinkingEnabled = enabled)
                    "codeExecutionEnabled" -> previous.copy(codeExecutionEnabled = enabled)
                    "googleSearchEnabled" -> previous.copy(googleSearchEnabled = enabled)
                    "openAiWebSearchEnabled" -> previous.copy(openAiWebSearchEnabled = enabled)
                    "openAiServiceTierEnabled" -> previous.copy(openAiServiceTierEnabled = enabled)
                    "webSearchEnabled" -> previous.copy(webSearchEnabled = enabled)
                    "shellEnabled" -> previous.copy(shellEnabled = enabled)
                    else -> previous
                }
            }
            val id = openId.value
            if (id == null) newChatSettings.value = update(newChatSettings.value ?: ConversationSettings())
            else settings.updateConversationSettings(id, update)
        } finally { actionId.value = command.actionId }
    }

    suspend fun attachmentCommand(command: WebSyncCommand) = ownerMutex.withLock {
        if (closed || command.seq != target.value.browserSeq) return@withLock
        try {
            val owner = retainedOwner ?: return@withLock
            if (submission.isFrozen(owner)) return@withLock
            val id = command.attachmentId ?: return@withLock
            val attachment = composers.state(owner).value.attachments.firstOrNull { it.localId == id }
                ?: return@withLock
            when (command.type) {
                "attachment_remove" -> composers.remove(owner, id)
                "attachment_retry" -> composers.retry(owner, id)
                "attachment_pdf" -> {
                    val pages = command.pages.toSet()
                    if (attachment.type == "pdf" && attachment.importState == com.newoether.agora.model.AttachmentImportState.PROCESSING &&
                        attachment.selectedPages == null && pages.isNotEmpty() && pages.all { it in 0 until (attachment.pageCount ?: 0) }) {
                        composers.configurePdf(owner, id, pages)
                    }
                }
                "attachment_video" -> {
                    val frames = command.frameCount
                    val interval = command.intervalMs
                    if (attachment.type == "video" && attachment.importState == com.newoether.agora.model.AttachmentImportState.PROCESSING &&
                        attachment.frameCount == null && frames != null && frames >= 2 && interval != null && interval in 0..Long.MAX_VALUE / 1000) {
                        composers.configureVideo(owner, id, frames, interval)
                    }
                }
            }
        } finally {
            actionId.value = command.actionId
        }
    }

    /** Preview pins a canonical artifact for the response; neither HTTP nor the browser chooses a path. */
    suspend fun previewAttachment(
        seq: Long, id: String, kind: String, index: Int,
        consume: suspend (File, String) -> Unit,
    ): Boolean {
        val work = scope.async {
            val previewOwner = Any()
            try {
                val selected = ownerMutex.withLock {
                    if (closed || seq != target.value.browserSeq || index < 0) return@withLock null
                    val owner = retainedOwner ?: return@withLock null
                    val attachment = composers.state(owner).value.attachments.firstOrNull { it.localId == id }
                        ?.takeIf { !it.unavailable && it.storage == com.newoether.agora.model.AttachmentStorage.APP_PRIVATE }
                        ?: return@withLock null
                    val artifact = when (kind) {
                        "page" -> attachment.takeIf { it.type == "pdf" }?.preRenderedPaths?.getOrNull(index)?.let { it to "image/jpeg" }
                        "frame" -> attachment.takeIf { it.type == "video" }?.processedFrames?.getOrNull(index)?.let { it to "image/jpeg" }
                        "source" -> if (index != 0) null else attachment.localPath?.let { path ->
                            when (attachment.type) {
                                "image" -> if (attachment.importState == com.newoether.agora.model.AttachmentImportState.READY) path to "image/jpeg" else null
                                "video" -> attachment.mimeType?.takeIf { VIDEO_MIME.matches(it) }?.let { path to it }
                                else -> null
                            }
                        }
                        else -> null
                    }
                    artifact?.also { AttachmentFiles.retainLivePath(previewOwner, it.first) }
                } ?: return@async false
                withContext(Dispatchers.IO) {
                    val file = try {
                        val root = uploadDirectory.toPath().toRealPath()
                        val path = File(selected.first).toPath().toRealPath()
                        path.takeIf { it.startsWith(root) && it != root && it.toFile().isFile }?.toFile()
                    } catch (_: java.io.IOException) {
                        null
                    } ?: return@withContext false
                    consume(file, selected.second)
                    true
                }
            } finally {
                AttachmentFiles.releaseLivePaths(previewOwner)
            }
        }
        return try { work.await() } finally { withContext(NonCancellable) { work.cancelAndJoin() } }
    }

    /** Pins the original Composer; HTTP work is cancelled with this connection, not the later selection. */
    suspend fun upload(
        seq: Long, fileName: String, mimeType: String?, forcedType: String?,
        expectedSize: Long?, input: ByteReadChannel,
    ): HttpStatusCode {
        val work = scope.async {
            val transportOwner = Any()
            val owner = ownerMutex.withLock {
                if (closed || seq != target.value.browserSeq) return@withLock null
                retainedOwner?.takeUnless(submission::isFrozen)?.also { composers.load(it) }
            } ?: return@async HttpStatusCode.Conflict
            var source: File? = null
            var imported = false
            try {
                val attachment = FileValidator.inspectAttachment(
                    "", AttachmentFiles.sanitizeFileName(fileName), mimeType, expectedSize,
                    forcedType, allowLocalSandbox(),
                ) ?: return@async HttpStatusCode.UnsupportedMediaType
                withContext(Dispatchers.IO) {
                    val file = File(uploadDirectory, "att_webui_${java.util.UUID.randomUUID()}.upload").also {
                        AttachmentFiles.retainLivePath(transportOwner, it.absolutePath)
                        source = it
                    }
                    var size = 0L
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.readAvailable(buffer)
                            if (count < 0) break
                            if (count.toLong() > AttachmentFiles.MAX_ATTACHMENT_BYTES - size) {
                                return@withContext HttpStatusCode.PayloadTooLarge
                            }
                            output.write(buffer, 0, count)
                            size += count
                        }
                    }
                    if (expectedSize != null && size != expectedSize) return@withContext HttpStatusCode.BadRequest
                    val received = attachment.copy(uri = file.absolutePath, localPath = file.absolutePath, fileSize = size)
                    ownerMutex.withLock {
                        if (!closed && !submission.isFrozen(owner)) {
                            imported = composers.importAttachment(owner, received)
                            if (imported) uploadedSources += received
                        }
                    }
                    if (imported) HttpStatusCode.Accepted else HttpStatusCode.Conflict
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HttpStatusCode.InternalServerError
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    AttachmentFiles.releaseLivePaths(transportOwner)
                    if (!imported) source?.delete()
                    composers.release(owner)
                }
            }
        }
        return try {
            work.await()
        } finally {
            withContext(NonCancellable) { work.cancelAndJoin() }
        }
    }
    suspend fun endUploads() = ownerMutex.withLock { closed = true }
    /** Connection children settle before this call, so no importer or Send outlives reclamation. */
    suspend fun close() = withContext(NonCancellable) {
        clients.detach(this@WebUiChatSession)
        ownerMutex.withLock {
            closed = true
            val abandoned = draftPersistence.ownerIds().flatMap { drafts.load(it).attachments }
            draftPersistence.clear()
            retainedOwner?.let { releaseOwner(it) }
            retainedOwner = null
            drafts.reclaimAttachments(abandoned + uploadedSources)
            uploadedSources.clear()
        }
    }

    /** Moves the composer retain to [ownerId]; the previous owner is released afterwards. */
    private suspend fun retainOwnerLocked(ownerId: String) {
        val previous = retainedOwner
        if (previous == ownerId) return
        composers.loadSelected(ownerId)
        retainedOwner = ownerId
        ownerState.value = ownerId to submission.observeState(ownerId)
        previous?.let { releaseOwner(it) }
    }

    private suspend fun releaseOwner(ownerId: String) {
        submission.releaseState(ownerId)
        composers.releaseSelected(ownerId)
    }

    private suspend fun moveByServer(conversationId: String?) {
        if (conversationId == null) newChatEntryId.incrementAndGet()
        target.value = OpenTarget(conversationId, target.value.browserSeq, movedByServer = true)
        openId.value = conversationId
        retainOwnerLocked(conversationId ?: NEW_CHAT_WORKSPACE_ID)
    }

    // -- ChatClient

    override val openConversationId: String? get() = openId.value
    override val renderStore: ConversationRenderStore get() = ui.renderStore
    override fun isConversationVisible(conversationId: String): Boolean = openId.value == conversationId
    override val branchTransitions = BranchReplacementTransitionCoordinator()

    override suspend fun awaitProjectedPath(conversationId: String, messageId: String) {
        combine(ui.messages, openId) { path, open ->
            open != conversationId || path.any { it.id == messageId }
        }.first { projectedOrClosed -> projectedOrClosed }
    }

    // The browser's message list owns bottom following for its own sends.
    override fun requestScrollToBottomAfter(conversationId: String, messageId: String, attachedOnly: Boolean) {
        val selected = target.value
        if (selected.conversationId == conversationId) {
            _scrollRequests.tryEmit(WebSyncEvent.ScrollToBottom(conversationId, messageId, selected.browserSeq))
        }
    }

    // Send haptics belong to the phone.
    override fun onSendAccepted(conversationId: String, messageId: String) = Unit

    override suspend fun applyCommittedNewConversationState(conversationId: String) {
        transfers.complete(conversationId)
    }

    override suspend fun publishAcceptedNewConversation(
        conversationId: String,
        modelId: String,
        entryId: Long,
    ): Boolean = ownerMutex.withLock {
        if (target.value.conversationId != null || newChatEntryId.get() != entryId) return@withLock false
        val remainingText = composers.state(NEW_CHAT_WORKSPACE_ID).value.text
        composers.load(conversationId)
        try {
            if (remainingText.isNotEmpty()) {
                composers.updateText(conversationId, remainingText)
                composers.persistText(conversationId, remainingText)
                composers.updateText(NEW_CHAT_WORKSPACE_ID, "")
                composers.persistText(NEW_CHAT_WORKSPACE_ID, "")
            }
            moveByServer(conversationId)
        } finally {
            composers.release(conversationId)
        }
        true
    }

    // The browser has no tree-mutation cover; it settles from the synced path.
    override suspend fun beginTreeMutation(conversationId: String, scrollToTarget: Boolean): Long? = null
    override fun settleTreeMutation(requestId: Long?, targetMessageId: String?) = Unit
    override fun failTreeMutation(requestId: Long?) = Unit

    override fun showSnackbar(message: String) {
        _snackbars.tryEmit(message)
    }

    override fun openConversation(conversationId: String) {
        scope.launch { ownerMutex.withLock { moveByServer(conversationId) } }
    }

    // The browser offers no share action.
    override fun showShareText(text: String) = Unit

    override fun settleDeletedConversation(conversationId: String) {
        scope.launch {
            ownerMutex.withLock {
                if (target.value.conversationId == conversationId) moveByServer(null)
            }
        }
    }

    override fun isSubmissionFrozen(conversationId: String): Boolean = submission.isFrozen(conversationId)

    override fun onGenerationActivityChanged(conversationId: String, active: Boolean) =
        if (active) ui.markActive(conversationId) else ui.markIdle(conversationId)

    private companion object {
        const val SNACKBAR_BUFFER = 8
        val VIDEO_MIME = Regex("video/[A-Za-z0-9.+-]+")
    }
}
