package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.*
import com.newoether.agora.tool.InteractiveAnswerToolProvider
import com.newoether.agora.tool.ToolCapabilityLoading
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises discovery through authorization, durable tool rounds, and request reconstruction. */
class GenerationCapabilityRequestTest {
    private val context = GenerationContext(interactiveAnswersEnabled = true)
    private val config = GenerationConfig(providerName = "provider", modelId = "model",
        apiKey = "unused", effectiveSystemPrompt = "User-owned prompt", codeExecutionEnabled = false,
        googleSearchEnabled = false, thinkingEnabled = false, baseUrl = null)
    private val user = MessageEntity(id = "user", conversationId = "chat", parentId = null,
        text = "Make a calculator", participant = Participant.USER, status = MessageStatus.SUCCESS,
        timestamp = 0, runId = "run")

    @Test fun `discovery authorizes only the next selected-path request and survives reconstruction`() = runTest {
        val provider = InteractiveAnswerToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val builder = GenerationApiPathBuilder(mockk<ConversationRepository>(relaxed = true), { it },
            toolDefinitions = executor)
        suspend fun build(parent: String, rows: List<MessageEntity>, ctx: GenerationContext = context) =
            builder.build(GenerationApiPathRequest(parent, "chat", config, ctx, rows))
        val first = build(user.id, listOf(user))
        assertEquals(listOf(ToolCapabilityLoading.NAME), first.providerConfig.tools!!.map { it.function.name })
        val identity = RunEffectIdentity("chat", 1L, "run", 0, "effect")
        val arguments = """{"capability":"interactive"}"""
        suspend fun execute(name: String, args: String, names: Set<String>) = executor.execute(
            AuthorizedToolCall(identity, "call", name, args, context, names), {},
        )
        // Guessing the deferred name in the discovery pass does not grant execution.
        assertTrue(execute(InteractiveAnswer.TOOL_NAME, "{}", setOf(ToolCapabilityLoading.NAME)).result.isError)
        val result = execute(ToolCapabilityLoading.NAME, arguments,
            first.providerConfig.tools!!.map { it.function.name }.toSet()).result
        assertFalse(result.isError)
        val placeholder = user.copy(id = "model-output", parentId = user.id, text = "",
            participant = Participant.MODEL, status = MessageStatus.TOOL_CALLING)
        val round = GenerationToolRoundBuilder().build(placeholder.id, "chat", "run", "model", "provider",
            listOf(ToolCallData(ToolCapabilityLoading.NAME, arguments, result.text, toolCallId = "call")), emptyList())
        val rows = listOf(user, placeholder) + round.entities
        val next = build(round.lastResultId, rows)
        assertEquals(next.messages.toString(), setOf(ToolCapabilityLoading.NAME, InteractiveAnswer.TOOL_NAME),
            next.providerConfig.tools!!.map { it.function.name }.toSet())
        assertEquals(config.effectiveSystemPrompt, next.providerConfig.systemPrompt)
        assertEquals(config.maxContextWindow - ContextTokenEstimator.estimateFixed(
            config.effectiveSystemPrompt, next.providerConfig.tools!!), next.providerConfig.maxContextWindow)
        // Reconstructing a request has no dependency on an executor-global activation cache.
        val reconstructed = GenerationApiPathBuilder(mockk<ConversationRepository>(relaxed = true), { it },
            toolDefinitions = GenerationToolExecutor.forTest(listOf(provider)))
            .build(GenerationApiPathRequest(round.lastResultId, "chat", config, context, rows))
        assertEquals(next.providerConfig.tools, reconstructed.providerConfig.tools)
        val disabled = build(round.lastResultId, rows, context.copy(interactiveAnswersEnabled = false))
        assertTrue(disabled.providerConfig.tools.orEmpty().isEmpty())
        val nextUser = user.copy(id = "next", parentId = round.lastResultId)
        assertEquals(listOf(ToolCapabilityLoading.NAME),
            build(nextUser.id, rows + nextUser).providerConfig.tools!!.map { it.function.name })
        // The sibling branch has no discovery result in its selected ancestry.
        val sibling = user.copy(id = "sibling", parentId = user.id)
        assertEquals(listOf(ToolCapabilityLoading.NAME),
            build(sibling.id, rows + sibling).providerConfig.tools!!.map { it.function.name })
        val lowContext = builder.build(GenerationApiPathRequest(round.lastResultId, "chat",
            config.copy(lowContextModeEnabled = true), context, rows))
        assertTrue(lowContext.providerConfig.tools.orEmpty().isEmpty())
    }
}
