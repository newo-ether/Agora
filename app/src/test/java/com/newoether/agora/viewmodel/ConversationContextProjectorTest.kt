package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationContextProjectorTest {
    @Test
    fun newChatProjectionIncludesTheCurrentlySelectedSystemPromptAndToolCost() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val admission = testGenerationAdmissionSnapshot(
            conversationId = "context-preview-conversation",
        )
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot(
                "context-preview-conversation",
                "provider:model",
                "prompt-selected-for-new-chat",
            )
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 221
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            newChatSystemPromptId = { "prompt-selected-for-new-chat" },
        )

        val projection = projector.project(null, null, "provider:model", 4_096)

        assertEquals(221, requireNotNull(projection.usage).estimatedTokenCount)
        assertTrue(projection.retainedMessageIds.orEmpty().isEmpty())
        coVerify(exactly = 1) {
            requestBuilder.captureContextProjectionSnapshot(
                "context-preview-conversation",
                "provider:model",
                "prompt-selected-for-new-chat",
            )
        }
        coVerify(exactly = 0) { conversations.restoreBranchSelections(any()) }
    }

    @Test
    fun projectionCountsFixedInputAndDurableToolPayloadInsteadOfUiStrippedRows() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val user = entity("user", null, Participant.USER, "question", 0)
        val model = entity("model", user.id, Participant.MODEL, "", 1).copy(
            status = MessageStatus.SENDING,
        )
        val tool = entity("${Constants.TOOL_MSG_PREFIX}call", model.id, Participant.MODEL, "", 2)
            .copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{\"command\":\"echo durable payload\"}",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
        val result = entity(
            "${Constants.RESULT_MSG_PREFIX}call",
            tool.id,
            Participant.USER,
            "durable result payload",
            3,
        ).copy(
            toolCallJson = Json.encodeToString(
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolArgs = "{}",
                        toolResult = "durable result payload",
                        toolCallId = "call-1",
                    ),
                ),
            ),
        )
        val loadedEntities = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model),
            allMessages = listOf(user, model, tool, result),
        )
        coEvery { contextLoader.load(any()) } returns DurableSelectedContext(
            messages = projectProviderMessages(loadedEntities, includeStoredTranscriptions = false),
            entities = loadedEntities,
        )
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 137
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val projection = projector.project("conversation", "{}", "provider:model", 4_096)

        assertTrue(requireNotNull(projection.usage).estimatedTokenCount > 137)
        assertEquals(4_096, requireNotNull(projection.usage).tokenBudget)
        assertTrue(tool.id in projection.retainedMessageIds.orEmpty())
        assertTrue(result.id in projection.retainedMessageIds.orEmpty())
    }

    @Test
    fun `a 96K context does not roll out under a 512K budget`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val user = entity(
            "user",
            null,
            Participant.USER,
            "context ".repeat(48_000),
            0,
        )
        val model = entity("model", user.id, Participant.MODEL, "answer", 1)
        val loaded = listOf(user, model)
        coEvery { contextLoader.load(any()) } returns DurableSelectedContext(
            messages = projectProviderMessages(loaded, includeStoredTranscriptions = false),
            entities = loaded,
        )
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every {
            generationManager.fixedContextTokenCost(snapshot.config, snapshot.context)
        } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val projection = projector.project("conversation", "{}", "provider:model", 524_288)

        assertTrue(requireNotNull(projection.usage).estimatedTokenCount < 524_288)
        assertEquals(setOf(user.id, model.id), projection.retainedMessageIds.orEmpty())
    }

    @Test
    fun `retained ordinary rows are one contiguous selected branch suffix`() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val firstUser = entity("user-1", null, Participant.USER, "first", 0)
        val firstModel = entity("model-1", firstUser.id, Participant.MODEL, "first answer", 1)
        val secondUser = entity("user-2", firstModel.id, Participant.USER, "second", 2)
        val secondModel = entity("model-2", secondUser.id, Participant.MODEL, "second answer", 3)
        val loaded = listOf(firstUser, firstModel, secondUser, secondModel)
        coEvery { contextLoader.load(any()) } returns DurableSelectedContext(
            messages = projectProviderMessages(loaded, includeStoredTranscriptions = false),
            entities = loaded,
        )
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every {
            generationManager.fixedContextTokenCost(snapshot.config, snapshot.context)
        } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val retained = projector.project("conversation", "{}", "provider:model", 524_288)
            .retainedMessageIds.orEmpty()
        val firstRetained = loaded.indexOfFirst { it.id in retained }

        assertTrue(firstRetained >= 0)
        assertTrue(loaded.drop(firstRetained).all { it.id in retained })
        assertEquals(loaded.map { it.id }.toSet(), retained)
    }

    @Test
    fun branchIdentityChangeReloadsCanonicalContext() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val first = entity("branch-first", null, Participant.USER, "first", 0)
        val second = entity("branch-second", null, Participant.USER, "second", 1)
        coEvery { contextLoader.load(any()) } returnsMany listOf(
            DurableSelectedContext(
                messages = projectProviderMessages(listOf(first), false),
                entities = listOf(first),
            ),
            DurableSelectedContext(
                messages = projectProviderMessages(listOf(second), false),
                entities = listOf(second),
            ),
        )
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )
        val firstSelection = """{"root":"branch-first"}"""
        val secondSelection = """{"root":"branch-second"}"""

        val firstProjection = projector.project(
            "conversation",
            firstSelection,
            "provider:model",
            4_096,
        )
        val secondProjection = projector.project(
            "conversation",
            secondSelection,
            "provider:model",
            4_096,
        )

        assertEquals(firstSelection, firstProjection.selectedBranchesJson)
        assertEquals(setOf(first.id), firstProjection.retainedMessageIds.orEmpty())
        assertEquals(secondSelection, secondProjection.selectedBranchesJson)
        assertEquals(setOf(second.id), secondProjection.retainedMessageIds.orEmpty())
        assertEquals(secondProjection, projector.projection.value)
        coVerify(exactly = 2) { contextLoader.load(any()) }
    }

    @Test
    fun loadingRecalculationRetainsUsageWithoutRetainedIdsUntilReplacement() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val first = entity("first", null, Participant.USER, "first", 0)
        val second = entity("second", null, Participant.USER, "second ".repeat(200), 1)
        val secondLoad = CompletableDeferred<DurableSelectedContext>()
        var loadIndex = 0
        coEvery { contextLoader.load(any()) } coAnswers {
            if (loadIndex++ == 0) {
                DurableSelectedContext(
                    messages = projectProviderMessages(listOf(first), false),
                    entities = listOf(first),
                )
            } else {
                secondLoad.await()
            }
        }
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val firstProjection = projector.project(
            "conversation",
            "first-selection",
            "provider:model",
            4_096,
        )
        val secondJob = launch {
            projector.project(
                "conversation",
                "second-selection",
                "provider:model",
                4_096,
            )
        }
        runCurrent()

        val loading = projector.projection.value
        assertTrue(loading.loading)
        assertFalse(loading.completed)
        assertEquals(firstProjection.usage, loading.usage)
        assertNull(loading.retainedMessageIds)

        secondLoad.complete(
            DurableSelectedContext(
                messages = projectProviderMessages(listOf(second), false),
                entities = listOf(second),
            ),
        )
        secondJob.join()

        val completed = projector.projection.value
        assertTrue(completed.completed)
        assertFalse(completed.loading)
        assertTrue(
            requireNotNull(completed.usage).estimatedTokenCount >
                requireNotNull(firstProjection.usage).estimatedTokenCount,
        )
        assertEquals(setOf(second.id), completed.retainedMessageIds.orEmpty())
    }

    @Test
    fun conversationSwitchAndInvalidationRetainOnlyTheDisplayedUsageWhileLoading() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val first = entity("first", null, Participant.USER, "first", 0)
        val second = entity("second", null, Participant.USER, "second", 1)
        val secondLoad = CompletableDeferred<DurableSelectedContext>()
        var loadIndex = 0
        coEvery { contextLoader.load(any()) } coAnswers {
            if (loadIndex++ == 0) {
                DurableSelectedContext(
                    messages = projectProviderMessages(listOf(first), false),
                    entities = listOf(first),
                )
            } else {
                secondLoad.await()
            }
        }
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation-a")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot(any(), "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val firstProjection = projector.project(
            "conversation-a",
            "selection-a",
            "provider:model",
            4_096,
        )
        val switchJob = launch {
            projector.project(
                "conversation-b",
                "selection-b",
                "provider:model",
                4_096,
            )
        }
        runCurrent()

        val switching = projector.projection.value
        assertEquals("conversation-b", switching.conversationId)
        assertEquals("selection-b", switching.selectedBranchesJson)
        assertTrue(switching.loading)
        assertEquals(firstProjection.usage, switching.usage)
        assertNull(switching.retainedMessageIds)

        secondLoad.complete(
            DurableSelectedContext(
                messages = projectProviderMessages(listOf(second), false),
                entities = listOf(second),
            ),
        )
        switchJob.join()
        val switched = projector.projection.value

        projector.invalidate("conversation-c")

        val invalidated = projector.projection.value
        assertEquals("conversation-c", invalidated.conversationId)
        assertTrue(invalidated.loading)
        assertFalse(invalidated.completed)
        assertEquals(switched.usage, invalidated.usage)
        assertNull(invalidated.retainedMessageIds)
    }

    @Test
    fun staleCompletionCannotReplaceTheLatestBranchProjection() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val first = entity("branch-first", null, Participant.USER, "first", 0)
        val second = entity("branch-second", null, Participant.USER, "second", 1)
        val firstContext = DurableSelectedContext(
            messages = projectProviderMessages(listOf(first), false),
            entities = listOf(first),
        )
        val secondContext = DurableSelectedContext(
            messages = projectProviderMessages(listOf(second), false),
            entities = listOf(second),
        )
        val firstLoad = CompletableDeferred<DurableSelectedContext>()
        val secondLoad = CompletableDeferred<DurableSelectedContext>()
        var loadIndex = 0
        coEvery { contextLoader.load(any()) } coAnswers {
            if (loadIndex++ == 0) firstLoad.await() else secondLoad.await()
        }
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )
        val firstSelection = """{"root":"branch-first"}"""
        val secondSelection = """{"root":"branch-second"}"""

        val firstJob = launch {
            projector.project("conversation", firstSelection, "provider:model", 4_096)
        }
        runCurrent()
        val secondJob = launch {
            projector.project("conversation", secondSelection, "provider:model", 4_096)
        }
        runCurrent()
        secondLoad.complete(secondContext)
        secondJob.join()

        assertEquals(secondSelection, projector.projection.value.selectedBranchesJson)
        assertEquals(setOf(second.id), projector.projection.value.retainedMessageIds.orEmpty())

        firstLoad.complete(firstContext)
        firstJob.join()

        assertEquals(secondSelection, projector.projection.value.selectedBranchesJson)
        assertEquals(setOf(second.id), projector.projection.value.retainedMessageIds.orEmpty())
    }

    @Test
    fun failedReloadPublishesNeutralProjectionWithoutRetainedIds() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val first = entity("first", null, Participant.USER, "first", 0)
        var loadIndex = 0
        coEvery { contextLoader.load(any()) } coAnswers {
            if (loadIndex++ == 0) {
                DurableSelectedContext(
                    messages = projectProviderMessages(listOf(first), false),
                    entities = listOf(first),
                )
            } else {
                throw IllegalStateException("load failed")
            }
        }
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 0
        every { generationManager.includesAssistantReasoning(any(), any()) } returns false
        stubFixedComposition(generationManager)
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
        )

        val previous = projector.project("conversation", "{}", "provider:model", 4_096)
        val projection = projector.project("conversation", "{}", "", 4_096)

        assertTrue(requireNotNull(previous.usage).estimatedTokenCount > 0)
        assertTrue(projection.completed)
        assertTrue(projection.failed)
        assertFalse(projection.loading)
        assertNull(projection.usage)
        assertNull(projection.retainedMessageIds)
        assertEquals(projection, projector.projection.value)
    }

    /**
     * The fixed-cost breakdown only feeds the context indicator's composition bar; these tests
     * assert totals and retained messages, so a zero split keeps them focused.
     */
    private fun stubFixedComposition(generationManager: GenerationManager) {
        every { generationManager.fixedContextComposition(any(), any()) } returns
            ContextTokenEstimator.FixedContextComposition(systemPromptTokens = 0, toolTokens = 0)
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        text: String,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = text,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "provider:model",
        runId = "run",
        runSequence = sequence,
    )
}
