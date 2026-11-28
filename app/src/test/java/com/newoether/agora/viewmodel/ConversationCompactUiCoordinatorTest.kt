package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationCompactUiCoordinatorTest {
    @Test
    fun nullConversationProjectsDefaultsWithoutCreatingRuntime() = runTest {
        val registry = mockk<ConversationStateRegistry>()
        val coordinator = coordinator(
            currentConversationId = MutableStateFlow(null),
            registry = registry,
            scope = backgroundScope,
        )
        runCurrent()

        assertFalse(coordinator.isCompacting.value)
        assertEquals("", coordinator.compactPreview.value)
        verify(exactly = 0) { registry.getOrCreate(any()) }
    }

    @Test
    fun projectionFiltersOrdinaryMessagesAndFollowsConversationSwitches() = runTest {
        val currentConversationId = MutableStateFlow<String?>("first")
        val registry = mockk<ConversationStateRegistry>()
        val firstStream = MutableStateFlow<ChatMessage?>(null)
        val secondStream = MutableStateFlow<ChatMessage?>(compactMessage("second compact"))
        every { registry.getOrCreate("first") } returns runtimeState(firstStream)
        every { registry.getOrCreate("second") } returns runtimeState(secondStream)
        val coordinator = coordinator(currentConversationId, registry, backgroundScope)
        runCurrent()

        firstStream.value = ordinaryMessage("ordinary")
        runCurrent()
        assertFalse(coordinator.isCompacting.value)
        assertEquals("", coordinator.compactPreview.value)

        firstStream.value = compactMessage("first compact")
        runCurrent()
        assertTrue(coordinator.isCompacting.value)
        assertEquals("first compact", coordinator.compactPreview.value)

        currentConversationId.value = "second"
        runCurrent()
        assertTrue(coordinator.isCompacting.value)
        assertEquals("second compact", coordinator.compactPreview.value)
    }

    @Test
    fun manualBuildsTheExactRequestAndReturnsTheTypedResult() = runTest {
        var captured: CompactRequest? = null
        val expected = CompactResult.Created("compact-id")
        val coordinator = coordinator(
            scope = backgroundScope,
            compactManual = { request ->
                captured = request
                expected
            },
        )

        val result = coordinator.manual("model", "prompt", 4)

        assertEquals(CompactRequest("model", "prompt", 4), captured)
        assertSame(expected, result)
    }

    @Test
    fun startedManualCompactPublishesTypedAndUnexpectedFailures() = runTest {
        val requests = mutableListOf<CompactRequest>()
        val failures = mutableListOf<String>()
        var throwUnexpected = false
        val coordinator = coordinator(
            scope = backgroundScope,
            compactManual = { request ->
                requests += request
                if (throwUnexpected) error("boom")
                CompactResult.Failed(CompactFailureReason.EMPTY_PROMPT)
            },
            failureMessage = { failed -> "failure:${failed.reason}" },
            onFailure = failures::add,
        )

        coordinator.startManual("model", "prompt", 2)
        runCurrent()
        throwUnexpected = true
        coordinator.startManual("other", "next", 3)
        runCurrent()

        assertEquals(
            listOf(
                CompactRequest("model", "prompt", 2),
                CompactRequest("other", "next", 3),
            ),
            requests,
        )
        assertEquals(
            listOf("failure:EMPTY_PROMPT", "failure:GENERIC"),
            failures,
        )
    }

    @Test
    fun recompactUsesConfiguredModelThenFallsBackToCurrentModel() = runTest {
        var configuredModel: String? = "configured"
        val requests = mutableListOf<CompactRequest>()
        val coordinator = coordinator(
            scope = backgroundScope,
            configuredModel = { configuredModel },
            currentModel = { "current" },
            configuredPrompt = { "saved prompt" },
            configuredRetainCount = { 5 },
            compactManual = { request ->
                requests += request
                CompactResult.Created(request.replaceMessageId.orEmpty())
            },
        )

        coordinator.startRecompact("first")
        runCurrent()
        configuredModel = " "
        coordinator.startRecompact("second")
        runCurrent()

        assertEquals(
            listOf(
                CompactRequest("configured", "saved prompt", 5, "first"),
                CompactRequest("current", "saved prompt", 5, "second"),
            ),
            requests,
        )
    }

    @Test
    fun cancellationPropagatesWithoutPublishingFailure() = runTest {
        val expected = CancellationException("cancel")
        val failures = mutableListOf<String>()
        val coordinator = coordinator(
            scope = backgroundScope,
            compactManual = { throw expected },
            onFailure = failures::add,
        )

        try {
            coordinator.manual("model", "prompt", 1)
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        coordinator.startManual("model", "prompt", 1)
        runCurrent()

        assertTrue(failures.isEmpty())
    }

    private fun coordinator(
        currentConversationId: StateFlow<String?> = MutableStateFlow(null),
        registry: ConversationStateRegistry = mockk(relaxed = true),
        scope: CoroutineScope,
        configuredModel: () -> String? = { null },
        currentModel: () -> String = { "current" },
        configuredPrompt: () -> String = { "prompt" },
        configuredRetainCount: () -> Int = { 0 },
        compactManual: suspend (CompactRequest) -> CompactResult = {
            CompactResult.Created("compact")
        },
        failureMessage: (CompactResult.Failed) -> String = { it.reason.name },
        onFailure: suspend (String) -> Unit = {},
    ) = ConversationCompactUiCoordinator(
        currentConversationId = currentConversationId,
        registry = registry,
        scope = scope,
        configuredModel = configuredModel,
        currentModel = currentModel,
        configuredPrompt = configuredPrompt,
        configuredRetainCount = configuredRetainCount,
        compactManual = compactManual,
        failureMessage = failureMessage,
        onFailure = onFailure,
        workDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
    )

    private fun runtimeState(stream: MutableStateFlow<ChatMessage?>) =
        mockk<ConversationGenerationState>().also { state ->
            every { state.streamingMessage } returns stream
        }

    private fun compactMessage(text: String) = ChatMessage(
        id = Constants.COMPACT_MSG_PREFIX + text,
        text = text,
        participant = Participant.MODEL,
    )

    private fun ordinaryMessage(text: String) = ChatMessage(
        id = "ordinary-$text",
        text = text,
        participant = Participant.MODEL,
    )
}
