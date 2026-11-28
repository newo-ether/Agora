package com.newoether.agora.tool

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolProviderTest {
    private val dao = mockk<ChatDao>()
    private val repository = mockk<ConversationRepository> { every { chatDao } returns dao }
    private val provider = TodoToolProvider(repository)
    private val identity = RunEffectIdentity("c", 1L, "r", 2, "effect")
    private val context = GenerationContext(conversationId = "c", toolEffectIdentity = identity)

    @Test
    fun definitionsRequireAnOpenedOwnerAndAreDisabledForCompact() {
        assertTrue(provider.definitions(GenerationContext()).isEmpty())
        assertTrue(provider.definitions(context.copy(todoEnabled = false)).isEmpty())
        assertEquals(listOf("todo_read", "todo_update"), provider.definitions(context).map { it.function.name })
    }

    @Test
    fun readsAndWholeListReplacementUseOnlyTheContextOwner() = runTest {
        val raw = """[{"content":"Task","status":"in_progress"}]"""
        coEvery { dao.getConversationTodo("c") } returns raw
        coEvery { dao.updateConversationTodo("c", raw, "r", 2, any()) } returns 1
        coEvery { dao.updateConversationTodo("c", null, "r", 2, any()) } returns 1
        val read = provider.executeOneShotResult("todo_read", "{}", context)
        assertEquals(raw, read.text)
        assertEquals(raw, read.structuredContent)
        val replaced = provider.executeOneShotResult("todo_update", """{"todos":$raw}""", context)
        assertEquals(raw, replaced.text)
        assertEquals("[]", provider.execute("todo_update", """{"todos":[]}""", context))
        coVerify(exactly = 1) { dao.getConversationTodo("c") }
        coVerify(exactly = 1) { dao.updateConversationTodo("c", raw, "r", 2, any()) }
        coVerify(exactly = 1) { dao.updateConversationTodo("c", null, "r", 2, any()) }
    }

    @Test
    fun invalidArgumentsAndMissingOrMismatchedIdentityNeverWrite() = runTest {
        val invalid = listOf(
            "{}", """{"todos":null}""", """{"todos":{}}""",
            """{"todos":[{"content":" ","status":"pending"}]}""",
            """{"todos":[{"content":"Task","status":"invalid"}]}""",
        )
        for (arguments in invalid) {
            assertTrue(runCatching { provider.execute("todo_update", arguments, context) }.isFailure)
        }
        for (ctx in listOf(
            context.copy(toolEffectIdentity = null),
            context.copy(conversationId = "other"),
            context.copy(todoEnabled = false),
        )) {
            assertTrue(runCatching { provider.execute("todo_update", """{"todos":[]}""", ctx) }.isFailure)
        }
        coVerify(exactly = 0) { dao.updateConversationTodo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun durableRejectionNeverReturnsSuccessfulUpdatedList() = runTest {
        coEvery { dao.updateConversationTodo(any(), any(), any(), any(), any()) } returns 0
        val failure = runCatching { provider.execute("todo_update", """{"todos":[]}""", context) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("no longer active"))
    }
}
