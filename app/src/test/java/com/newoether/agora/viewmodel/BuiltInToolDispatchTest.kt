package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.tool.BuiltInToolNames
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BuiltInToolDispatchTest {
    @Test
    fun aliasesDispatchThroughProductionExecutorAndKeepFrozenAuthorization() = runTest {
        val received = mutableListOf<String>()
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()
            override fun handles(name: String) = name in BuiltInToolNames.legacyToCanonical.values
            override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
                received += name
                assertEquals("{}", arguments)
                return "done"
            }
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        BuiltInToolNames.legacyToCanonical.forEach { (legacy, canonical) ->
            val call = AuthorizedToolCall(RunEffectIdentity("c", 1L, "r", 0, "effect"), "id", legacy, "{}",
                GenerationContext(), setOf(canonical))
            val executed = executor.execute(call) {}
            assertFalse(legacy, executed.result.isError)
            assertEquals(canonical, received.last())
            assertEquals(call.batchIdentity, executed.batchIdentity)
            assertEquals(call.callId, executed.callId)
            val count = received.size
            assertTrue(executor.execute(call.copy(authorizedToolNames = emptySet())) {}.result.isError)
            assertEquals(count, received.size)
            assertFalse(executor.execute(call.copy(name = canonical, authorizedToolNames = setOf(legacy))) {}.result.isError)
        }
        val unknown = AuthorizedToolCall(RunEffectIdentity("c", 1L, "r", 0, "effect"), "unknown", "unknown", "{}",
            GenerationContext(), setOf("unknown"))
        assertEquals("Unknown tool: unknown", executor.execute(unknown) {}.result.text)
    }
}
