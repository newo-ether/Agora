package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactControllerTest {
    @Test
    fun disabledSettingShortCircuitsBeforeReadingDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val compactor = ContextCompactor(
            conversations = conversations,
            generationErrorFormatter = { it },
        )

        assertFalse(
            compactor.automaticNeeded(
                "conversation",
                4096,
                automaticConfig().copy(enabled = false),
            ),
        )
        coVerify(exactly = 0) {
            conversations.getProviderContextTopologySnapshot(any())
            conversations.withReadSnapshot<Any?>(any())
        }
    }

    @Test
    fun automaticCompactUsesOrdinaryPathAndOnlySpecializesGenerationParameters() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = true)
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        val pathRequest = slot<GenerationApiPathRequest>()
        val launchRequest = slot<StandardGenerationContinuationRequest>()
        val completedJob = Job().apply { complete() }

        stubSelectedPath(
            conversations,
            listOf(source),
            mapOf(null to source.id),
        )
        coEvery { manager.buildApiPath(capture(pathRequest)) } returns GenerationApiPath(
            messages = listOf(source.toUi()),
            providerConfig = mockk<ProviderConfig>(),
        )
        every { launcher.launch(capture(launchRequest), state) } returns
            StandardGenerationContinuationLaunch(
                job = completedJob,
                modelMessageId = "compact_message",
                started = CompletableDeferred(true),
                uiToken = 1L,
            )

        val started = controller(
            conversations,
            operation,
            requestBuilder,
            manager,
            launcher,
        ).startAutomaticStandard(
            conversationId = "conversation",
            contextLimit = 4096,
            config = automaticConfig(),
            state = state,
        )

        assertNotNull(started)
        assertEquals(source.id, pathRequest.captured.parentId)
        assertEquals(null, pathRequest.captured.loadedMessages)
        assertEquals(source.id, launchRequest.captured.parentMessageId)
        assertTrue(launchRequest.captured.modelMessageId!!.startsWith("compact_"))
        assertEquals(null, launchRequest.captured.replacementMessageId)
        assertEquals("compact", launchRequest.captured.requestKind)
        assertFalse(launchRequest.captured.touchConversationOnAdmission)
        assertNull(launchRequest.captured.conversationModelId)
        assertEquals("compact prompt", launchRequest.captured.snapshot.config.effectiveSystemPrompt)
        assertEquals(
            BuiltInPrompts.CONTEXT_COMPACT_USER,
            launchRequest.captured.snapshot.config.initialUserPrompt,
        )
        assertTrue(launchRequest.captured.queueDrainRequiresSuccess)
        assertFalse(launchRequest.captured.continuesAfterSuccess)
        assertEquals(1L, started!!.uiToken)
        assertFalse(launchRequest.captured.alreadyHoldsConversationLock)
        controller(
            conversations,
            operation,
            requestBuilder,
            manager,
            launcher,
        ).startAutomaticStandard(
            conversationId = "conversation",
            contextLimit = 4096,
            config = automaticConfig(),
            state = state,
            alreadyHoldsConversationLock = true,
            continuesAfterSuccess = true,
        )
        // Headless Task callers forward their held automation lease to the ordinary launcher.
        assertTrue(launchRequest.captured.alreadyHoldsConversationLock)
        assertTrue(launchRequest.captured.continuesAfterSuccess)
        assertFalse(launchRequest.captured.snapshot.config.thinkingEnabled)
        assertFalse(launchRequest.captured.snapshot.context.webSearchEnabled)
        assertFalse(launchRequest.captured.snapshot.context.shellEnabled)
        val finalText = launchRequest.captured.transformFinalText(
            "<context_summary>\nsummary\n</context_summary>",
            MessageStatus.SUCCESS,
        )
        assertTrue(finalText.contains("summary"))
        assertFalse(finalText.contains("context_summary"))
        assertTrue(finalText.contains("[User]"))
        state.dispose()
        Unit
    }

    @Test
    fun preservedCompactKeepsCapturedSystemPromptAndPrefixesCompactPromptIntoUserMessage() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation()
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        val launchRequest = slot<StandardGenerationContinuationRequest>()
        var compactMessageId: String? = null

        stubSelectedPath(
            conversations,
            listOf(source),
            mapOf(null to source.id),
        )
        coEvery {
            requestBuilder.captureAdmissionSnapshot(
                conversationId = "conversation",
                runId = any(),
                modelId = "provider:model",
            )
        } returns testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "compact-preflight-run",
        )
        coEvery { manager.buildApiPath(any()) } returns GenerationApiPath(
            messages = listOf(source.toUi()),
            providerConfig = mockk<ProviderConfig>(),
        )
        every { launcher.launch(capture(launchRequest), state) } answers {
            val request = firstArg<StandardGenerationContinuationRequest>()
            compactMessageId = requireNotNull(request.modelMessageId)
            StandardGenerationContinuationLaunch(
                job = Job().apply { complete() },
                modelMessageId = requireNotNull(request.modelMessageId),
                started = CompletableDeferred(true),
                uiToken = 1L,
            )
        }
        coEvery { conversations.getMessage(any()) } answers {
            val requestedId = firstArg<String>()
            if (requestedId == source.id) {
                source
            } else if (requestedId == compactMessageId) {
                MessageEntity(
                    id = requireNotNull(compactMessageId),
                    conversationId = "conversation",
                    parentId = source.id,
                    text = "summary",
                    status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL,
                    timestamp = 2L,
                    modelName = "provider:model",
                    runId = "compact-run",
                    runSequence = 0,
                )
            } else {
                null
            }
        }

        val result = controller(
            conversations,
            operation,
            requestBuilder,
            manager,
            launcher,
        ).manual(
            conversationId = "conversation",
            request = CompactRequest(
                model = "provider:model",
                prompt = "compact prompt",
                retainLogicalMessages = 2,
                preserveSystemPrompt = true,
            ),
            state = state,
        )

        assertTrue(result is CompactResult.Created)
        assertNull(launchRequest.captured.conversationModelId)
        assertEquals(
            "system",
            launchRequest.captured.snapshot.config.effectiveSystemPrompt,
        )
        assertEquals(
            "compact prompt\n\n" + BuiltInPrompts.CONTEXT_COMPACT_USER,
            launchRequest.captured.snapshot.config.initialUserPrompt,
        )
        state.dispose()
        Unit
    }

    @Test
    fun recompactLaunchesTheSameRowAtTheSameParentWithoutTouchingSuffix() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation()
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        val target = compactEntity(parentId = source.id)
        val suffix = sourceEntity(
            id = "suffix",
            parentId = target.id,
            runId = "suffix-run",
            participant = Participant.MODEL,
        )
        val selected = mapOf<String?, String>(
            null to source.id,
            source.id to target.id,
            target.id to suffix.id,
        )
        val before = listOf(source, target, suffix)
        val settled = before.map {
            if (it.id == target.id) it.copy(text = "new summary", status = MessageStatus.SUCCESS)
            else it
        }
        stubSelectedPath(conversations, before, selected)
        coEvery { conversations.getMessage(target.id) } returnsMany listOf(
            target,
            settled.single { it.id == target.id },
        )
        coEvery {
            requestBuilder.captureAdmissionSnapshot(
                conversationId = "conversation",
                runId = any(),
                modelId = "provider:model",
            )
        } returns testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "compact-preflight-run",
        )
        coEvery { manager.buildApiPath(any()) } returns GenerationApiPath(
            messages = listOf(source.toUi()),
            providerConfig = mockk<ProviderConfig>(),
        )
        val launchRequest = slot<StandardGenerationContinuationRequest>()
        every { launcher.launch(capture(launchRequest), state) } returns
            StandardGenerationContinuationLaunch(
                job = Job().apply { complete() },
                modelMessageId = target.id,
                started = CompletableDeferred(true),
                uiToken = 1L,
            )

        val result = controller(
            conversations,
            operation,
            requestBuilder,
            manager,
            launcher,
        ).manual(
            conversationId = "conversation",
            request = CompactRequest(
                model = "provider:model",
                prompt = "new prompt",
                retainLogicalMessages = 2,
                replaceMessageId = target.id,
            ),
            state = state,
        )

        assertEquals(CompactResult.Created(target.id), result)
        assertEquals(target.id, launchRequest.captured.modelMessageId)
        assertEquals(target.id, launchRequest.captured.replacementMessageId)
        assertEquals(source.id, launchRequest.captured.parentMessageId)
        assertEquals("compact-preflight-run", launchRequest.captured.snapshot.runId)
        assertEquals("compact", launchRequest.captured.requestKind)
        assertTrue(launchRequest.captured.touchConversationOnAdmission)
        assertNull(launchRequest.captured.conversationModelId)
        assertEquals(suffix, before.single { it.id == suffix.id })
        coVerify(exactly = 0) {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun emptyPreflightProjectionCannotReplaceOrBlockTheOrdinaryGenerationPath() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = true)
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        stubSelectedPath(
            conversations,
            listOf(source),
            mapOf(null to source.id),
        )
        coEvery { manager.buildApiPath(any()) } returns GenerationApiPath(
            messages = emptyList(),
            providerConfig = mockk<ProviderConfig>(),
        )
        every { launcher.launch(any(), state) } returns StandardGenerationContinuationLaunch(
            job = Job().apply { complete() },
            modelMessageId = "compact_message",
            started = CompletableDeferred(true),
            uiToken = 1L,
        )

        val started = controller(
            conversations,
            operation,
            mockk(),
            manager,
            launcher,
        ).startAutomaticStandard(
            conversationId = "conversation",
            contextLimit = 4096,
            config = automaticConfig(),
            state = state,
        )

        assertNotNull(started)
        io.mockk.verify(exactly = 1) { launcher.launch(any(), state) }
        state.dispose()
        Unit
    }

    @Test
    fun requiredAutomaticCompactLaunchFailureStopsInsteadOfReportingNotNeeded() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = true)
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        stubSelectedPath(
            conversations,
            listOf(source),
            mapOf(null to source.id),
        )
        coEvery { manager.buildApiPath(any()) } returns GenerationApiPath(
            messages = listOf(source.toUi()),
            providerConfig = mockk<ProviderConfig>(),
        )
        every { launcher.launch(any(), state) } returns null

        val result = controller(
            conversations,
            operation,
            mockk(),
            manager,
            launcher,
        ).startAutomaticBeforeSend(
            "conversation",
            4096,
            automaticConfig(),
            state,
        )

        assertTrue(result is CompactResult.Failed)
        state.dispose()
        Unit
    }

    @Test
    fun automaticNotNeededNeverClaimsOrLaunchesGeneration() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = false)
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})

        val started = controller(
            conversations,
            operation,
            mockk(),
            mockk(),
            launcher,
        ).startAutomaticBeforeSend(
            "conversation",
            4096,
            automaticConfig(),
            state,
        )

        assertEquals(CompactResult.NotNeeded, started)
        io.mockk.verify(exactly = 0) { launcher.launch(any(), any()) }
        state.dispose()
        Unit
    }

    @Test
    fun stoppedManualCompactReturnsStoppedWithoutExposingGeneratedBody() = runBlocking {
        val result = manualCompactResult(MessageStatus.STOPPED)

        assertTrue(result is CompactResult.Stopped)
        assertTrue((result as CompactResult.Stopped).messageId.startsWith("compact_"))
    }

    @Test
    fun failedManualCompactReturnsOnlyPersistedErrorSegment() = runBlocking {
        val result = manualCompactResult(
            status = MessageStatus.ERROR,
            errorSegment = "provider failed",
        )

        assertTrue(result is CompactResult.Failed)
        assertEquals("provider failed", (result as CompactResult.Failed).externalDetail)
        assertEquals(CompactFailureReason.GENERIC, result.reason)
        assertFalse(result.externalDetail.orEmpty().contains("full generated compact body"))
    }

    private suspend fun manualCompactResult(
        status: MessageStatus,
        errorSegment: String? = null,
    ): CompactResult {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation()
        val manager = mockk<GenerationManager>()
        val launcher = mockk<StandardGenerationContinuationLauncher>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val source = sourceEntity()
        var compactMessageId: String? = null

        stubSelectedPath(
            conversations,
            listOf(source),
            mapOf(null to source.id),
        )
        coEvery { conversations.getMessage(any()) } answers {
            val requestedId = firstArg<String>()
            if (requestedId == source.id) {
                source
            } else {
                compactMessageId
                    ?.takeIf { it == requestedId }
                    ?.let { settledId ->
                        MessageEntity(
                            id = settledId,
                            conversationId = "conversation",
                            parentId = source.id,
                            text = "full generated compact body",
                            status = status,
                            participant = Participant.MODEL,
                            timestamp = 2L,
                            modelName = "provider:model",
                            toolCallJson = errorSegment?.let { error ->
                                """[{"type":"answer","content":"full generated compact body"},{"type":"error","content":"$error"}]"""
                            },
                            runId = "compact-run",
                            runSequence = 0,
                        )
                    }
            }
        }
        coEvery {
            requestBuilder.captureAdmissionSnapshot(
                conversationId = "conversation",
                runId = any(),
                modelId = "provider:model",
            )
        } returns testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "compact-preflight-run",
        )
        coEvery { manager.buildApiPath(any()) } returns GenerationApiPath(
            messages = listOf(source.toUi()),
            providerConfig = mockk<ProviderConfig>(),
        )
        every { launcher.launch(any(), state) } answers {
            val request = firstArg<StandardGenerationContinuationRequest>()
            val messageId = requireNotNull(request.modelMessageId)
            compactMessageId = messageId
            StandardGenerationContinuationLaunch(
                job = Job().apply { complete() },
                modelMessageId = messageId,
                started = CompletableDeferred(true),
                uiToken = 1L,
            )
        }

        val result = controller(
            conversations,
            operation,
            requestBuilder,
            manager,
            launcher,
        ).manual(
            conversationId = "conversation",
            request = CompactRequest(
                model = "provider:model",
                prompt = "compact prompt",
                retainLogicalMessages = 2,
            ),
            state = state,
        )
        state.dispose()
        return result
    }

    private fun stubSelectedPath(
        conversations: ConversationRepository,
        entities: List<MessageEntity>,
        selections: Map<String?, String>,
    ) {
        val snapshot = ProviderContextTopologySnapshot(
            selectedBranchesJson = Json.encodeToString(
                selections.mapKeys { (key, _) -> key ?: "null" },
            ),
            messages = entities.map { entity ->
                MessageContextTopology(
                    id = entity.id,
                    conversationId = entity.conversationId,
                    parentId = entity.parentId,
                    status = entity.status,
                    participant = entity.participant,
                    timestamp = entity.timestamp,
                    modelName = entity.modelName,
                    runId = entity.runId,
                    runSequence = entity.runSequence,
                    consumedAtPass = entity.consumedAtPass,
                )
            },
        )
        coEvery {
            conversations.getProviderContextTopologySnapshot("conversation")
        } returns snapshot
        val byId = entities.associateBy(MessageEntity::id)
        coEvery { conversations.getMessage(any()) } answers {
            byId[firstArg<String>()]
        }
    }

    private fun controller(
        conversations: ConversationRepository,
        operation: ContextCompactOperation,
        requestBuilder: GenerationRequestBuilder,
        manager: GenerationManager,
        launcher: StandardGenerationContinuationLauncher,
    ) = ConversationCompactController(
        conversations = conversations,
        operation = operation,
        requestBuilder = requestBuilder,
        generationManagerProvider = { manager },
        continuationLauncher = { launcher },
    )

    private fun automaticConfig(): AutomaticCompactConfig =
        testGenerationAdmissionSnapshot().automaticCompact

    private fun sourceEntity(
        id: String = "source",
        parentId: String? = null,
        runId: String = "source-run",
        participant: Participant = Participant.USER,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = 1L,
        modelName = "provider:model",
        runId = runId,
        runSequence = 0,
    )

    private fun compactEntity(parentId: String) = MessageEntity(
        id = "compact_boundary",
        conversationId = "conversation",
        parentId = parentId,
        text = "old summary",
        status = MessageStatus.SUCCESS,
        participant = Participant.MODEL,
        timestamp = 2L,
        modelName = "provider:model",
        runId = "compact-run",
        runSequence = 0,
    )

    private fun MessageEntity.toUi() = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        status = status,
        timestamp = timestamp,
        modelName = modelName,
        runId = runId,
        runSequence = runSequence,
    )
}

private class FakeCompactOperation(
    private val automaticNeeded: Boolean = true,
) : ContextCompactOperation {
    override suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean = automaticNeeded
}
