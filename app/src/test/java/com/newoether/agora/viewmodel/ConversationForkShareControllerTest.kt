package com.newoether.agora.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationForkShareControllerTest {
    @Test
    fun missingConversationMakesEveryIntentANoOp() = runTest {
        val fixture = Fixture(currentConversationId = null, scope = this)

        assertFalse(fixture.controller.fork(fixture.origin, "message") { fixture.results += it })
        fixture.controller.shareConversation(fixture.origin)
        fixture.controller.shareGeneration(fixture.origin, "assistant")
        fixture.controller.shareMessages(fixture.origin, setOf("message"))
        runCurrent()

        coVerify(exactly = 0) { fixture.service.fork(any(), any()) }
        coVerify(exactly = 0) { fixture.service.shareAll(any()) }
        coVerify(exactly = 0) { fixture.service.shareRun(any(), any()) }
        coVerify(exactly = 0) { fixture.service.shareMessages(any(), any()) }
        fixture.assertNoOutputs()
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun emptyMessageSelectionIsRejectedBeforeLaunchingServiceWork() = runTest {
        val fixture = Fixture(scope = this)

        fixture.controller.shareMessages(fixture.origin, emptySet())
        runCurrent()

        coVerify(exactly = 0) { fixture.service.shareMessages(any(), any()) }
        fixture.assertNoOutputs()
    }

    @Test
    fun forkSuccessSelectsTheCreatedConversation() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.fork("conversation", "through") } returns
            ConversationForkShareService.ForkResult.Success("fork")

        assertTrue(fixture.controller.fork(fixture.origin, "through") { fixture.results += it })
        runCurrent()

        assertEquals(listOf("fork"), fixture.forkedConversationIds)
        assertEquals(listOf(true), fixture.results)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun forkFailureIsLocalizedAndReported() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.fork("conversation", null) } returns
            ConversationForkShareService.ForkResult.Failure("broken")

        assertTrue(fixture.controller.fork(fixture.origin) { fixture.results += it })
        runCurrent()

        assertEquals(listOf("fork: broken"), fixture.failures)
        assertEquals(listOf(false), fixture.results)
        assertTrue(fixture.forkedConversationIds.isEmpty())
    }

    @Test
    fun shareIntentsPreserveTheirExactServiceArguments() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.shareAll("conversation") } returns
            ConversationForkShareService.ShareResult.Success("all")
        coEvery { fixture.service.shareRun("conversation", "assistant") } returns
            ConversationForkShareService.ShareResult.Success("run")
        coEvery { fixture.service.shareMessages("conversation", setOf("one", "two")) } returns
            ConversationForkShareService.ShareResult.Success("selection")

        fixture.controller.shareConversation(fixture.origin)
        fixture.controller.shareGeneration(fixture.origin, "assistant")
        fixture.controller.shareMessages(fixture.origin, setOf("one", "two"))
        runCurrent()

        assertEquals(listOf("all", "run", "selection"), fixture.shareTexts)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun shareFailureIsLocalizedAndReported() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.shareRun("conversation", "assistant") } returns
            ConversationForkShareService.ShareResult.Failure("unfinished")

        fixture.controller.shareGeneration(fixture.origin, "assistant")
        runCurrent()

        assertEquals(listOf("share: unfinished"), fixture.failures)
        assertTrue(fixture.shareTexts.isEmpty())
    }

    @Test
    fun staleForkFinishesOnceWithoutNavigationOrFailureOutput() = runTest {
        val fixture = Fixture(scope = this)
        val result = CompletableDeferred<ConversationForkShareService.ForkResult>()
        coEvery { fixture.service.fork("conversation", null) } coAnswers { result.await() }
        var current = true
        fixture.controller.fork(fixture.origin, isCurrent = { current }) { fixture.results += it }
        runCurrent()
        current = false
        result.complete(ConversationForkShareService.ForkResult.Success("fork"))
        runCurrent()
        fixture.assertNoOutputs()
        assertEquals(listOf(false), fixture.results)
    }

    @Test
    fun staleShareCompletesWithoutExportingItsText() = runTest {
        val fixture = Fixture(scope = this)
        val result = CompletableDeferred<ConversationForkShareService.ShareResult>()
        coEvery { fixture.service.shareAll("conversation") } coAnswers { result.await() }
        var current = true
        val outputs = mutableListOf<String?>()
        fixture.controller.shareConversation(fixture.origin, { current }) { outputs += it }
        runCurrent()
        current = false
        result.complete(ConversationForkShareService.ShareResult.Success("export"))
        runCurrent()
        fixture.assertNoOutputs()
        assertEquals(listOf<String?>(null), outputs)
    }

    private class Fixture(
        currentConversationId: String? = "conversation",
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val service = mockk<ConversationForkShareService>()
        val origin = FakeChatClient(open = currentConversationId)
        val forkedConversationIds: List<String> get() = origin.openedConversations
        val shareTexts: List<String> get() = origin.shareTexts
        val failures: List<String> get() = origin.snackbars
        val results = mutableListOf<Boolean>()
        val controller = ConversationForkShareController(
            service = service,
            scope = scope,
            forkFailureText = { "fork: $it" },
            shareFailureText = { "share: $it" },
        )

        fun assertNoOutputs() {
            assertTrue(forkedConversationIds.isEmpty())
            assertTrue(shareTexts.isEmpty())
            assertTrue(failures.isEmpty())
        }
    }
}
