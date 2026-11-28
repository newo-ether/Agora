package com.newoether.agora.tool

import com.newoether.agora.automation.SubagentManager
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SubagentToolProviderTest {
    private val manager = mockk<SubagentManager>()
    private val provider = SubagentToolProvider(manager)
    private val context = GenerationContext(conversationId = "parent", subagentToolsEnabled = true,
        enabledModelIds = listOf("Provider:one", "Provider:two"),
        subagentModelIds = listOf("Provider:one", "Provider:two"))

    @Test fun definitionsExposeOnlyEnabledIdsAndNoToolsInChildrenOrCompact() {
        assertTrue(provider.definitions(context.copy(subagentToolsEnabled = false)).isEmpty())
        val definitions = provider.definitions(context)
        assertEquals(listOf("agent_spawn", "agent_send_message", "agent_wait", "agent_list", "agent_stop"),
            definitions.map { it.function.name })
        val description = definitions.first().function.description
        assertTrue(description.contains("Provider:one"))
        assertTrue(description.contains("Provider:two"))
        assertFalse(description.contains("price"))
        val sendDescription = definitions.single { it.function.name == "agent_send_message" }.function.description
        assertTrue(sendDescription.contains("idle or busy"))
        assertTrue(sendDescription.contains("queued item receipt"))
        assertTrue(sendDescription.contains("does not interrupt"))
    }

    @Test fun deleteToolAppearsAndRunsOnlyWhenItsSettingIsEnabled() = runTest {
        assertFalse(provider.definitions(context).any { it.function.name == "agent_delete" })
        assertTrue(provider.executeOneShotResult("agent_delete", """{"agent_id":"child"}""", context).isError)
        coVerify(exactly = 0) { manager.delete(any(), any()) }
        val enabled = context.copy(subagentDeleteEnabled = true)
        assertEquals("agent_delete", provider.definitions(enabled).last().function.name)
        assertTrue(provider.definitions(enabled.copy(subagentToolsEnabled = false)).isEmpty())
        coEvery { manager.delete("child", enabled) } returns Unit
        val result = provider.executeOneShotResult("agent_delete", """{"agent_id":"child"}""", enabled)
        assertFalse(result.isError)
        coVerify(exactly = 1) { manager.delete("child", enabled) }
        coEvery { manager.delete("busy", enabled) } throws IllegalStateException("Subagent is running")
        val rejected = provider.executeOneShotResult("agent_delete", """{"agent_id":"busy"}""", enabled)
        assertTrue(rejected.isError && rejected.text.contains("Subagent is running"))
    }

    @Test fun configuredCandidatesAndOnlyValidRecommendationsAppearInDescriptions() {
        val restricted = context.copy(subagentModelIds = listOf("Provider:two"),
            subagentRecommendedModel = "Provider:two")
        val spawn = provider.definitions(restricted).first().function
        assertFalse(spawn.description.contains("Provider:one"))
        assertTrue(spawn.description.contains("Provider:two"))
        assertTrue(spawn.description.contains("hint only"))
        assertTrue(spawn.parameters.required.contains("model_id"))
        val invalid = provider.definitions(restricted.copy(subagentRecommendedModel = "Provider:one"))
            .first().function.description
        assertFalse(invalid.contains("Recommended model ID"))
        val empty = provider.definitions(restricted.copy(subagentModelIds = emptyList())).first().function.description
        assertTrue(empty.contains("Available model IDs: []"))
        assertFalse(empty.contains("Provider:one") || empty.contains("Provider:two"))
        assertEquals(5, provider.definitions(restricted.copy(subagentModelIds = emptyList())).size)
    }

    @Test fun nullAndEmptyCandidatesDoNotExposeGlobalIdsOrReachSpawnManager() = runTest {
        for (models in listOf<List<String>?>(null, emptyList())) {
            val unavailable = context.copy(subagentModelIds = models,
                subagentRecommendedModel = "Provider:one")
            val description = provider.definitions(unavailable).first().function.description
            assertTrue(description.contains("Available model IDs: []"))
            assertFalse(description.contains("Provider:one") || description.contains("Provider:two"))
            assertFalse(description.contains("Recommended model ID"))
            assertEquals(5, provider.definitions(unavailable).size)
            assertTrue(provider.executeOneShotResult("agent_spawn",
                """{"name":"Worker","model_id":"Provider:one","task":"Task"}""", unavailable).isError)
        }
        coVerify(exactly = 0) { manager.spawn(any(), any(), any(), any()) }
    }

    @Test fun recommendationNeverFillsMissingModelOrOverridesAnExplicitChoice() = runTest {
        val recommended = context.copy(subagentModelIds = context.enabledModelIds,
            subagentRecommendedModel = "Provider:two")
        assertTrue(provider.executeOneShotResult("agent_spawn",
            """{"name":"Worker","task":"Task"}""", recommended).isError)
        coVerify(exactly = 0) { manager.spawn(any(), any(), any(), any()) }
        coEvery { manager.spawn("Worker", "Provider:one", "Task", recommended) } returns "child"
        assertFalse(provider.executeOneShotResult("agent_spawn",
            """{"name":"Worker","model_id":"Provider:one","task":"Task"}""", recommended).isError)
        coVerify(exactly = 1) { manager.spawn("Worker", "Provider:one", "Task", recommended) }
        coVerify(exactly = 0) { manager.spawn(any(), "Provider:two", any(), any()) }
    }

    @Test fun spawnAndWaitReturnExactConversationAndCompletionReceipts() = runTest {
        coEvery { manager.spawn("Worker", "Provider:one", "Task", context) } returns "child"
        val spawn = Json.parseToJsonElement(provider.execute("agent_spawn",
            """{"name":"Worker","model_id":"Provider:one","task":"Task"}""", context)).jsonObject
        assertEquals("child", spawn["agent_id"]?.jsonPrimitive?.content)
        coEvery { manager.wait(listOf("child"), 50L, context) } returns
            listOf("child" to SubagentCompletion("completion", "completed", "Answer"))
        val wait = Json.parseToJsonElement(provider.execute("agent_wait",
            """{"agent_ids":["child"],"timeout_ms":50}""", context)).jsonObject
        assertEquals("completion", wait["subagent_receipts"]?.jsonObject?.get("child")?.jsonPrimitive?.content)
        assertEquals("Answer", wait["results"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test fun waitReturnsFailureDetailAndCodeWithoutReplacingAnswerOrReceipt() = runTest {
        coEvery { manager.wait(listOf("child"), 50L, context) } returns listOf("child" to
            SubagentCompletion("completion", "failed", "Partial answer", "model",
                errorText = "Connection closed", errorCode = "connection_closed"))
        val result = Json.parseToJsonElement(provider.execute("agent_wait",
            """{"agent_ids":["child"],"timeout_ms":50}""", context)).jsonObject
        val failure = result.getValue("results").jsonArray.single().jsonObject
        assertEquals("failed", failure.getValue("status").jsonPrimitive.content)
        assertEquals("Partial answer", failure.getValue("text").jsonPrimitive.content)
        assertEquals("Connection closed", failure.getValue("error").jsonPrimitive.content)
        assertEquals("connection_closed", failure.getValue("error_code").jsonPrimitive.content)
        assertEquals("completion", result.getValue("subagent_receipts").jsonObject.getValue("child").jsonPrimitive.content)
    }

    @Test fun sendReturnsExactQueuedReceiptWithoutClaimingExecution() = runTest {
        coEvery { manager.send("child", "Follow up", context) } returns "queued-message"
        val result = provider.executeOneShotResult("agent_send_message",
            """{"agent_id":"child","message":"Follow up"}""", context)
        assertFalse(result.isError)
        val receipt = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("child", receipt["agent_id"]?.jsonPrimitive?.content)
        assertEquals("queued", receipt["status"]?.jsonPrimitive?.content)
        assertEquals("queued-message", receipt["queued_item_id"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { manager.send("child", "Follow up", context) }
    }

    @Test fun sendFailureReturnsAnErrorWithoutAQueuedReceipt() = runTest {
        coEvery { manager.send("child", "Follow up", context) } throws IllegalStateException("Stale parent pass")
        val result = provider.executeOneShotResult("agent_send_message",
            """{"agent_id":"child","message":"Follow up"}""", context)
        assertTrue(result.isError)
        assertTrue(result.text.contains("Stale parent pass"))
        assertFalse(result.text.contains("queued_item_id"))
        assertTrue(provider.executeOneShotResult("agent_send_message", """{"agent_id":"child"}""", context).isError)
        coVerify(exactly = 1) { manager.send(any(), any(), any()) }
    }

    @Test fun disabledAndInvalidToolsNeverReachManagerAndCancellationPropagates() = runTest {
        assertTrue(provider.executeOneShotResult("agent_spawn", "{}", context).isError)
        assertTrue(provider.executeOneShotResult("agent_list", "{}",
            context.copy(subagentToolsEnabled = false)).isError)
        coVerify(exactly = 0) { manager.spawn(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.list(any()) }
        coEvery { manager.stop("child", context) } throws CancellationException("Stop")
        assertTrue(runCatching {
            provider.executeOneShotResult("agent_stop", """{"agent_id":"child"}""", context)
        }.exceptionOrNull() is CancellationException)
    }
}
