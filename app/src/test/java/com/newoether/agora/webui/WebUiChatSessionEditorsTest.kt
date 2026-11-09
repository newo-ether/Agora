package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.presentation.validGenerationParameters
import com.newoether.agora.presentation.withGenerationParameters
import com.newoether.agora.viewmodel.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebUiChatSessionEditorsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shared = MutableStateFlow(mapOf("a" to ConversationSettings(thinkingEnabled = false, temperature = 0.7f)))
    private val preserve = MutableStateFlow(true)
    private val compactModel = MutableStateFlow<String?>("OpenAI:compact")
    private val prompts = MutableStateFlow(listOf(com.newoether.agora.data.SystemPromptEntry(id = "prompt", title = "Prompt")))
    private val conversation = MutableStateFlow(ChatConversation("a", "Test", modelId = "OpenAI:test"))
    private val streaming = MutableStateFlow<com.newoether.agora.model.ChatMessage?>(null)
    private val stopping = MutableStateFlow(false)
    private val generating = MutableStateFlow(false)
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns MutableStateFlow(ConversationGenerationSnapshot())
        every { this@mockk.generating } returns this@WebUiChatSessionEditorsTest.generating
        every { queueMutationMutex } returns kotlinx.coroutines.sync.Mutex()
        every { this@mockk.stopping } returns this@WebUiChatSessionEditorsTest.stopping
        every { streamingMessage } returns streaming
        every { queuedSends } returns MutableStateFlow(emptyList())
    }
    private val settings = mockk<SettingsRepository> {
        every { selectedModel } returns MutableStateFlow("OpenAI:test")
        every { enabledModels } returns MutableStateFlow(setOf("OpenAI:test", "OpenAI:compact"))
        every { developerOptionsEnabled } returns MutableStateFlow(false)
        every { debugModelEnabled } returns MutableStateFlow(false)
        every { modelAliases } returns MutableStateFlow(emptyMap())
        every { modelProviderNames } returns MutableStateFlow(emptyMap())
        every { systemPrompts } returns prompts
        every { activeSystemPromptId } returns MutableStateFlow("prompt")
        every { customProviders } returns MutableStateFlow(emptyList())
        every { conversationSettings } returns shared
        every { codeExecutionEnabled } returns MutableStateFlow(false)
        every { googleSearchEnabled } returns MutableStateFlow(false)
        every { thinkingEnabled } returns MutableStateFlow(true)
        every { thinkingLevel } returns MutableStateFlow("medium")
        every { thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { openAiServiceTier } returns MutableStateFlow("auto")
        every { openAiResponsesApiEnabled } returns MutableStateFlow(false)
        every { webSearchEnabled } returns MutableStateFlow(true)
        every { shellEnabled } returns MutableStateFlow(true)
        every { localLowContextModeEnabled } returns MutableStateFlow(false)
        every { maxContextWindow } returns MutableStateFlow(32768)
        every { defaultTemperature } returns MutableStateFlow(0.6f)
        every { defaultMaxTokens } returns MutableStateFlow(null)
        every { defaultTopP } returns MutableStateFlow(null)
        every { defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { defaultPresencePenalty } returns MutableStateFlow(null)
        every { contextCompactModel } returns compactModel
        every { contextCompactPrompt } returns MutableStateFlow("configured summary")
        every { contextCompactRetainCount } returns MutableStateFlow(4)
        every { contextCompactPreserveSystemPrompt } returns preserve
        coEvery { awaitInitialLoad() } just Runs
        every { updateConversationSettings(any(), any()) } answers {
            val id = firstArg<String>()
            shared.value = shared.value + (id to secondArg<(ConversationSettings) -> ConversationSettings>()(shared.value[id] ?: ConversationSettings()))
        }
    }
    private val conversations = mockk<ConversationRepository>(relaxed = true) {
        every { observeConversation(any()) } answers {
            if (firstArg<String>() == "a") conversation else flowOf(ChatConversation(firstArg(), "Test", modelId = "OpenAI:test"))
        }
        coEvery { updateConversationSystemPrompt("a", any()) } coAnswers {
            conversation.value = conversation.value.copy(systemPromptId = secondArg())
            true
        }
        every { observeMessageTopology(any()) } returns flowOf(emptyList())
        coEvery { getConversation(any()) } returns null
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }.apply { stubOpenRecovery() }
    private val generation = mockk<MessageGenerationController> {
        coEvery { compactManual(any(), any()) } returns CompactResult.NotNeeded
    }
    private val session by lazy { WebUiChatSession(
        generation, mockk<GenerationStopAdapter>(relaxed = true), ChatClients(), conversations,
        mockk<ConversationStateRegistry> { every { getOrCreate(any()) } returns state },
        ConversationExecutionCoordinator(), settings, mockk<ConversationSettingsTransferCoordinator>(relaxed = true),
        mockk<AttachmentImportProcessor>(), scope, temporary.newFolder(), { it.reason.name },
    ) }
    @After fun finish() { scope.cancel() }
    private suspend fun ready(id: String? = null, seq: Long = 0): WebUiChatSession.ComposerState {
        session.open(id, seq)
        return withTimeout(5_000) { session.composerState.first { it.conversationId == id && it.seq == seq && it.modelValid } }
    }
    @Test fun deleteReceiptWaitsForCompletionAndRejectsStaleDuplicateAndCancelledWork() = runBlocking {
        ready("a", 4)
        val row = com.newoether.agora.model.ChatMessage(id = "u", text = "input",
            participant = com.newoether.agora.model.Participant.USER)
        var completion: ((Boolean) -> Unit)? = null
        every { generation.deleteMessage(any(), "a", row.id, any(), any()) } answers {
            completion = arg(4)
            1
        }
        val receipts = mutableListOf<WebSyncEvent.RowAction>()
        val command = WebSyncCommand("delete", conversationId = "a", seq = 4, messageId = row.id, actionId = 8)
        session.rowCommand(command.copy(seq = 3), listOf(row)) { receipts += it }
        assertNull(completion)
        val pending = async { session.rowCommand(command, listOf(row)) { receipts += it } }
        withTimeout(5_000) { while (completion == null) yield() }
        assertTrue(receipts.isEmpty())
        assertFalse(pending.isCompleted)
        completion!!(false)
        pending.await()
        assertEquals(listOf(WebSyncEvent.RowAction("a", 4, 8, emptyList(), false)), receipts)
        session.rowCommand(command, listOf(row)) { receipts += it }
        verify(exactly = 1) { generation.deleteMessage(any(), "a", row.id, any(), any()) }
        completion = null
        val cancelled = async { session.rowCommand(command.copy(actionId = 9), listOf(row)) { receipts += it } }
        withTimeout(5_000) { while (completion == null) yield() }
        cancelled.cancelAndJoin()
        completion!!(true)
        assertEquals(1, receipts.size)
        completion = null
        val replaced = async { session.rowCommand(command.copy(actionId = 10), listOf(row)) { receipts += it } }
        withTimeout(5_000) { while (completion == null) yield() }
        withTimeout(5_000) { ready("b", 5) }
        completion!!(true)
        replaced.await()
        assertEquals(1, receipts.size)
    }
    @Test fun conversationDeletionUsesNewChatForRootsAndMissingParents() = runBlocking {
        for (parent in listOf(null, "missing-parent")) {
            ready("a", 1)
            session.settleDeletedConversation("a", parent)
            val destination = withTimeout(5_000) {
                session.openTarget.first { it.conversationId == null && it.movedByServer }
            }
            assertEquals(1L, destination.browserSeq)
        }
    }
    @Test fun conversationDeletionDoesNotReplaceANewerExplicitSelection() = runBlocking {
        ready("a", 1)
        ready("b", 2)
        val before = session.openTarget.value
        session.settleDeletedConversation("a", "parent")
        ready("b", 3)
        assertEquals(before.copy(browserSeq = 3L), session.openTarget.value)
        coVerify(exactly = 0) { conversations.getConversation("parent") }
    }
    @Test fun wholeTreeDeleteKeepsItsReceiptAfterTheCanonicalServerMoveToParent() = runBlocking {
        ready("a", 4)
        val row = com.newoether.agora.model.ChatMessage(id = "u", text = "input",
            participant = com.newoether.agora.model.Participant.USER)
        var completion: ((Boolean) -> Unit)? = null
        every { generation.deleteMessage(any(), "a", row.id, any(), any()) } answers {
            completion = arg(4)
            1
        }
        val receipts = mutableListOf<WebSyncEvent.RowAction>()
        val command = WebSyncCommand("delete", conversationId = "a", seq = 4, messageId = row.id, actionId = 8)
        val pending = async { session.rowCommand(command, listOf(row)) { receipts += it } }
        withTimeout(5_000) { while (completion == null) yield() }
        coEvery { conversations.getConversation("parent") } returns ChatEntity("parent", "Parent")
        session.settleDeletedConversation("a", "parent")
        withTimeout(5_000) { session.openTarget.first { it.conversationId == "parent" && it.movedByServer } }
        completion!!(true)
        pending.await()
        assertEquals(listOf(WebSyncEvent.RowAction("a", 4, 8, emptyList(), true)), receipts)
        completion = null
        ready("a", 5)
        val explicitExit = async {
            session.rowCommand(command.copy(seq = 5, actionId = 9), listOf(row)) { receipts += it }
        }
        withTimeout(5_000) { while (completion == null) yield() }
        ready(null, 6)
        completion!!(true)
        explicitExit.await()
        assertEquals(1, receipts.size)
        completion = null
        ready("a", 7)
        val disconnected = async {
            session.rowCommand(command.copy(seq = 7, actionId = 10), listOf(row)) { receipts += it }
        }
        withTimeout(5_000) { while (completion == null) yield() }
        session.endUploads()
        completion!!(true)
        disconnected.await()
        assertEquals(1, receipts.size)
    }

    @Test fun failedBranchSelectionPublishesCommittedReceiptBeforeRethrowing() = runBlocking {
        val user = com.newoether.agora.model.ChatMessage(id = "u", text = "question", participant = com.newoether.agora.model.Participant.USER,
            timestamp = 1, runId = "input")
        val first = user.copy(id = "m1", parentId = "u", participant = com.newoether.agora.model.Participant.MODEL,
            timestamp = 2, runId = "first")
        val second = first.copy(id = "m2", timestamp = 3, runId = "second")
        val graph = listOf(user, first, second)
        val snapshot = com.newoether.agora.data.local.ProviderContextTopologySnapshot(
            """{"null":"u","u":"m1"}""", graph.map { message ->
                com.newoether.agora.data.local.MessageContextTopology(message.id, "a", message.parentId,
                    message.status, message.participant, message.timestamp, modelName = null,
                    runId = message.runId!!, runSequence = 0, consumedAtPass = null)
            })
        coEvery { conversations.getProviderContextTopologySnapshot("a") } returns snapshot
        val failure = IllegalStateException("fixture mutation failure")
        coEvery { conversations.selectRunBranch("a", "input", "second", any()) } throws failure
        ready("a", 6)
        val command = WebSyncCommand("branch", conversationId = "a", seq = 6, actionId = 12,
            messageId = "m1", direction = 1, branchAnchorMessageId = "m1", branchAnchorParentId = "u",
            branchIndex = 0, totalBranches = 2)
        val receipts = mutableListOf<WebSyncEvent.RowAction>()
        val thrown = runCatching { session.rowCommand(command, listOf(user, first)) { receipts += it } }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(1, receipts.size)
        assertEquals(listOf("u", "m1"), receipts.single().path.map { it.id })
        assertEquals(12L, receipts.single().actionId)
        assertFalse(receipts.single().success)
    }
    @Test fun branchSelectionRevalidatesTopologyAndRejectsStaleDuplicateInvalidAndBusyCommands() = runBlocking {
        val user = com.newoether.agora.model.ChatMessage(id = "u", text = "question", timestamp = 1,
            participant = com.newoether.agora.model.Participant.USER, runId = "run-input")
        val first = user.copy(id = "m1", parentId = "u", participant = com.newoether.agora.model.Participant.MODEL,
            timestamp = 2, runId = "run-first")
        val second = first.copy(id = "m2", timestamp = 3, runId = "run-second")
        val graph = listOf(user, first, second)
        var selected = first.id
        fun snapshot() = com.newoether.agora.data.local.ProviderContextTopologySnapshot(
            """{"null":"u","u":"$selected"}""", graph.map { message ->
                com.newoether.agora.data.local.MessageContextTopology(message.id, "a", message.parentId,
                    message.status, message.participant, message.timestamp, modelName = null,
                    runId = message.runId!!, runSequence = 0, consumedAtPass = null)
            })
        coEvery { conversations.getProviderContextTopologySnapshot("a") } answers { snapshot() }
        coEvery { conversations.selectRunBranch("a", "run-input", "run-second", any()) } coAnswers {
            selected = arg<Map<String?, String>>(3).getValue("u")
        }
        session.start()
        ready("a", 5)
        val path = listOf(user, first)
        val command = WebSyncCommand("branch", conversationId = "a", seq = 5, messageId = first.id,
            actionId = 10, direction = 1, branchAnchorMessageId = first.id, branchAnchorParentId = "u",
            branchIndex = 0, totalBranches = 2)
        session.rowCommand(command.copy(seq = 4), path)
        session.rowCommand(command.copy(conversationId = "b"), path)
        session.rowCommand(command.copy(actionId = 1, direction = 0), path)
        session.rowCommand(command.copy(actionId = 2, direction = -1), path)
        session.rowCommand(command.copy(actionId = 3, branchAnchorMessageId = "old"), path)
        session.rowCommand(command.copy(actionId = 4, totalBranches = 3), path)
        session.rowCommand(command.copy(actionId = 5), listOf(user))
        generating.value = true
        session.rowCommand(command.copy(actionId = 6), path)
        generating.value = false
        stopping.value = true
        session.rowCommand(command.copy(actionId = 7), path)
        stopping.value = false
        coVerify(exactly = 0) { conversations.selectRunBranch(any(), any(), any(), any()) }
        val before = snapshot().messages
        var receipt: WebSyncEvent.RowAction? = null
        session.rowCommand(command, path) { receipt = it }
        val selectedReceipt = requireNotNull(receipt)
        assertEquals(listOf("u", "m2"), selectedReceipt.path.map { it.id })
        assertEquals(10L, selectedReceipt.actionId)
        assertEquals(5L, selectedReceipt.seq)
        assertTrue(selectedReceipt.success)
        session.rowCommand(command, path)
        session.rowCommand(command.copy(actionId = 11), path)
        coVerify(exactly = 1) { conversations.selectRunBranch("a", "run-input", "run-second", mapOf(null to "u", "u" to "m2")) }
        assertEquals("m2", selected)
        assertEquals(before, snapshot().messages)
        assertEquals(11L, session.composerState.first().actionId)
        session.endUploads()
        session.rowCommand(command.copy(actionId = 12), path)
        coVerify(exactly = 1) { conversations.selectRunBranch(any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversations.upsertConversation(any()) }
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }

    @Test fun advancedSaveUpdatesOnlySixFieldsAndResetLeavesCurrentToolsIntact() = runBlocking {
        session.start()
        val initial = ready()
        assertEquals(0.6f, initial.generationDefaults.temperature)
        assertEquals("configured summary", initial.compactDefaults!!.prompt)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingEnabled", enabled = false))
        session.editorCommand(WebSyncCommand("advanced", parameters = ConversationSettings(temperature = 1.2f, thinkingEnabled = true)))
        val local = session.composerState.first()
        assertEquals(1.2f, local.generationParameters.temperature)
        assertEquals(false, local.generationParameters.thinkingEnabled)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        ready("a", 1)
        shared.value = shared.value + ("a" to shared.value.getValue("a").copy(shellEnabled = false))
        val draft = ConversationSettings(contextWindow = 65536, temperature = 1f, maxTokens = 8192,
            topP = 0.5f, frequencyPenalty = -1f, presencePenalty = 2f, shellEnabled = true)
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 1, parameters = draft))
        assertEquals(draft.withGenerationParameters(draft).copy(thinkingEnabled = false, shellEnabled = false), shared.value.getValue("a"))
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 1, parameters = ConversationSettings()))
        assertEquals(ConversationSettings(thinkingEnabled = false, shellEnabled = false), shared.value.getValue("a"))
    }
    @Test fun promptSelectionStaysLocalAndSharedWritesRejectStaleMissingAndClosedRequests() = runBlocking {
        session.start()
        ready()
        session.editorCommand(WebSyncCommand("system_prompt", value = "prompt", actionId = 1))
        assertEquals("prompt", session.composerState.first().systemPromptId)
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        ready("a", 2)
        val command = WebSyncCommand("system_prompt", conversationId = "a", seq = 2, value = "prompt", actionId = 2)
        listOf(command.copy(seq = 1), command.copy(conversationId = "b"), command.copy(value = "missing")).forEach { session.editorCommand(it) }
        assertNull(conversation.value.systemPromptId)
        session.editorCommand(command)
        assertEquals("prompt", session.composerState.first().systemPromptId)
        session.editorCommand(command.copy(value = null, actionId = 3))
        assertNull(session.composerState.first().systemPromptId)
        ready(null, 3)
        assertEquals("prompt", session.composerState.first().systemPromptId)
        session.endUploads()
        session.editorCommand(command.copy(conversationId = null, seq = 3, value = null))
        assertEquals("prompt", session.composerState.first().systemPromptId)
        coVerify(exactly = 2) { conversations.updateConversationSystemPrompt("a", any()) }
        coVerify(exactly = 0) { conversations.upsertConversation(any()) }
    }
    @Test fun advancedRefusesInvalidStaleAndClosedRequestsWithoutMutation() = runBlocking {
        session.start()
        ready("a", 3)
        val original = shared.value
        listOf(ConversationSettings(temperature = Float.NaN), ConversationSettings(contextWindow = 20),
            ConversationSettings(maxTokens = 0), ConversationSettings(topP = 2f),
            ConversationSettings(frequencyPenalty = -3f), ConversationSettings(presencePenalty = Float.POSITIVE_INFINITY)).forEach {
            assertFalse(validGenerationParameters(it))
            session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 3, actionId = 2, parameters = it))
        }
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 2, parameters = ConversationSettings(temperature = 1f)))
        session.editorCommand(WebSyncCommand("advanced", conversationId = "old", seq = 3, parameters = ConversationSettings(temperature = 1f)))
        session.endUploads()
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 3, parameters = ConversationSettings(temperature = 1f)))
        assertEquals(original, shared.value)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }
    @Test fun compactKeepsCapturedOwnerRequestAndPreservePreferenceAcrossSelectionChange() = runBlocking {
        val started = CompletableDeferred<CompactRequest>()
        val release = CompletableDeferred<Unit>()
        coEvery { generation.compactManual("a", any()) } coAnswers {
            started.complete(secondArg()); release.await(); CompactResult.Created("compact")
        }
        session.start()
        ready("a", 7)
        val original = shared.value
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 7, actionId = 11, modelId = "OpenAI:compact", text = "own summary", retainCount = 0))
        val captured = withTimeout(5_000) { started.await() }
        assertEquals(11L, session.composerState.first().actionId)
        preserve.value = false
        ready("b", 8)
        release.complete(Unit)
        coVerify(timeout = 5_000, exactly = 1) { generation.compactManual("a", CompactRequest("OpenAI:compact", "own summary", 0, preserveSystemPrompt = true)) }
        assertTrue(captured.preserveSystemPrompt)
        assertEquals(0L, session.composerState.first().actionId)
        assertEquals(original, shared.value)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }
    @Test fun recompactUsesTheSelectedCompactAndCapturedConfiguredRequest() = runBlocking {
        val started = CompletableDeferred<CompactRequest>()
        val release = CompletableDeferred<Unit>()
        coEvery { generation.compactManual("a", any()) } coAnswers {
            started.complete(secondArg()); release.await(); CompactResult.Created("compact_target")
        }
        session.start()
        ready("a", 9)
        val target = com.newoether.agora.model.ChatMessage(id = "compact_target", text = "summary",
            participant = com.newoether.agora.model.Participant.MODEL)
        val command = WebSyncCommand("recompact", conversationId = "a", seq = 9, messageId = target.id)
        session.rowCommand(command.copy(seq = 8), listOf(target))
        session.rowCommand(command.copy(conversationId = "other"), listOf(target))
        session.rowCommand(command, emptyList())
        session.editorCommand(command.copy(messageId = null, modelId = "OpenAI:compact", text = "summary", retainCount = 0))
        assertFalse(started.isCompleted)
        session.rowCommand(command, listOf(target))
        val request = withTimeout(5_000) { started.await() }
        assertEquals(CompactRequest("OpenAI:compact", "configured summary", 4,
            replaceMessageId = target.id, preserveSystemPrompt = true), request)
        preserve.value = false
        ready("b", 10)
        release.complete(Unit)
        coVerify(timeout = 5_000, exactly = 1) { generation.compactManual("a", request) }
        assertEquals("b", session.openConversationId)
        coVerify(exactly = 0) { conversations.upsertConversation(any()) }
    }
    @Test fun recompactUsesTheCurrentModelForBlankConfigurationAndReportsFailure() = runBlocking {
        compactModel.value = ""
        coEvery { generation.compactManual("a", any()) } returns CompactResult.Failed(CompactFailureReason.GENERIC)
        session.start()
        ready("a", 4)
        val notice = async(start = CoroutineStart.UNDISPATCHED) { session.snackbars.first() }
        val target = com.newoether.agora.model.ChatMessage(id = "compact_target", text = "summary",
            participant = com.newoether.agora.model.Participant.MODEL)
        session.rowCommand(WebSyncCommand("recompact", conversationId = "a", seq = 4, messageId = target.id), listOf(target))
        assertEquals("GENERIC", withTimeout(5_000) { notice.await() })
        coVerify(exactly = 1) { generation.compactManual("a", CompactRequest("OpenAI:test", "configured summary", 4,
            replaceMessageId = target.id, preserveSystemPrompt = true)) }
    }
    @Test fun compactRejectsNewChatStaleInvalidAndStoppingRequests() = runBlocking {
        session.start()
        ready()
        val command = WebSyncCommand("compact", modelId = "OpenAI:compact", text = "summary", retainCount = 4)
        session.editorCommand(command)
        ready("a", 4)
        val selected = command.copy(conversationId = "a", seq = 4)
        listOf(command, selected.copy(modelId = "missing"), selected.copy(text = " "),
            selected.copy(retainCount = -1), selected.copy(retainCount = null)).forEach { session.editorCommand(it) }
        stopping.value = true
        session.editorCommand(selected)
        stopping.value = false
        streaming.value = com.newoether.agora.model.ChatMessage(id = com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX + "active",
            participant = com.newoether.agora.model.Participant.MODEL, text = "", status = com.newoether.agora.model.MessageStatus.SENDING)
        withTimeout(5_000) { session.composerState.first { it.compacting } }
        session.editorCommand(selected)
        coVerify(exactly = 0) { generation.compactManual(any(), any()) }
    }
    @Test fun compactFailureUsesCanonicalFormatterAndAcknowledgesWithoutSettingsWrites() = runBlocking {
        coEvery { generation.compactManual(any(), any()) } returns CompactResult.Failed(CompactFailureReason.GENERATION_BUSY)
        session.start()
        ready("a", 1)
        val failure = async(start = CoroutineStart.UNDISPATCHED) { session.snackbars.first() }
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, actionId = 9, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        assertEquals("GENERATION_BUSY", withTimeout(5_000) { failure.await() })
        withTimeout(5_000) { session.composerState.first { it.actionId == 9L } }
        coEvery { generation.compactManual(any(), any()) } throws IllegalStateException("test failure")
        val generic = async(start = CoroutineStart.UNDISPATCHED) { session.snackbars.first() }
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, actionId = 10, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        assertEquals("GENERIC", withTimeout(5_000) { generic.await() })
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }
    @Test fun disconnectCancelsOnlySessionWaitAndDoesNotReplayCompact() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { generation.compactManual(any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        session.start()
        ready("a", 1)
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        withTimeout(5_000) { started.await() }
        scope.cancel()
        withTimeout(5_000) { cancelled.await() }
        coVerify(exactly = 1) { generation.compactManual(any(), any()) }
    }
}
