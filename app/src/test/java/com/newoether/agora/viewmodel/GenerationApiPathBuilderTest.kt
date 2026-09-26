package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.api.util.ContextTokenEstimator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationApiPathBuilderTest {
    @Test
    fun `caller snapshot produces compact-bounded path and exact provider config`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { listOf(toolDefinition()) },
        )
        val compact = message("${Constants.COMPACT_MSG_PREFIX}boundary", parentId = "old", sequence = 1)
        val user = message("user", parentId = compact.id, sequence = 2, participant = Participant.USER)
        val model = message("model", parentId = user.id, sequence = 3)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = model.id,
                conversationId = "conversation",
                config = generationConfig().copy(anthropicCacheEnabled = false, anthropicCacheTtl = "5m"),
                context = GenerationContext(),
                loadedMessages = listOf(message("old", null, 0), compact, user, model),
            ),
        )

        assertEquals(listOf(compact.id, user.id, model.id), path.messages.map { it.id })
        assertEquals("model-id", path.providerConfig.modelId)
        assertFalse(path.providerConfig.anthropicCacheEnabled)
        assertEquals("5m", path.providerConfig.anthropicCacheTtl)
        assertEquals("system", path.providerConfig.systemPrompt)
        assertEquals(listOf(toolDefinition()), path.providerConfig.tools)
        assertEquals(
            generationConfig().maxContextWindow -
                ContextTokenEstimator.estimateFixed("system", listOf(toolDefinition())),
            path.providerConfig.maxContextWindow,
        )
    }

    @Test
    fun `low context mode omits system prompt tools and their fixed token cost`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        var definitionReads = 0
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = {
                definitionReads++
                error("Low Context Mode must not read tool definitions")
            },
        )
        val user = message("user", null, 0, Participant.USER)
        val config = generationConfig().copy(
            effectiveSystemPrompt = null,
            lowContextModeEnabled = true,
        )

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = user.id,
                conversationId = "conversation",
                config = config,
                context = GenerationContext(),
                loadedMessages = listOf(user),
            ),
        )

        assertEquals(0, definitionReads)
        assertEquals(null, path.providerConfig.systemPrompt)
        assertTrue(path.providerConfig.tools.orEmpty().isEmpty())
        assertEquals(
            config.maxContextWindow -
                ContextTokenEstimator.estimateFixed(null, emptyList()),
            path.providerConfig.maxContextWindow,
        )
    }
    @Test
    fun `deepseek chat with tools enables ordinary reasoning accounting`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val user = message("user", null, 0, Participant.USER)
        suspend fun build(config: GenerationConfig, withTools: Boolean = true) =
            GenerationApiPathBuilder(
                conversations = repository,
                generationErrorFormatter = { it },
                toolDefinitions = { if (withTools) listOf(toolDefinition()) else emptyList() },
            ).build(
                GenerationApiPathRequest(
                    parentId = user.id,
                    conversationId = "conversation",
                    config = config,
                    context = GenerationContext(),
                    loadedMessages = listOf(user),
                ),
            ).providerConfig.includeAssistantReasoning
        val deepSeek = generationConfig(Constants.PROVIDER_DEEPSEEK).copy(
            modelId = "deepseek-chat",
            thinkingEnabled = true,
        )
        assertTrue(build(deepSeek))
        assertTrue(build(deepSeek.copy(providerName = "Relay")))
        assertFalse(build(deepSeek, withTools = false))
        assertFalse(build(deepSeek.copy(responsesApiEnabled = true)))
        assertFalse(build(deepSeek.copy(thinkingEnabled = false)))
    }

    @Test
    fun `nearest normally completed compact on the parent chain is the context boundary`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { emptyList() },
        )

        for (invalidStatus in listOf(
            MessageStatus.ERROR,
            MessageStatus.STOPPED,
            MessageStatus.SENDING,
            MessageStatus.THINKING,
        )) {
            val old = message("old-$invalidStatus", null, 0, Participant.USER)
            val successful = message(
                "${Constants.COMPACT_MSG_PREFIX}successful-$invalidStatus",
                old.id,
                1,
            )
            val middle = message("middle-$invalidStatus", successful.id, 2, Participant.USER)
            val invalid = message(
                "${Constants.COMPACT_MSG_PREFIX}invalid-$invalidStatus",
                middle.id,
                3,
            ).copy(status = invalidStatus)
            val latest = message("latest-$invalidStatus", invalid.id, 4, Participant.USER)

            val path = builder.build(
                GenerationApiPathRequest(
                    parentId = latest.id,
                    conversationId = "conversation",
                    config = generationConfig(),
                    context = GenerationContext(),
                    loadedMessages = listOf(old, successful, middle, invalid, latest),
                ),
            )

            assertEquals(successful.id, path.messages.first().id)
            assertTrue(path.messages.none { it.id == old.id })
            val prepared = prepareMessages(path.messages, path.providerConfig.maxContextWindow)
            assertEquals("context_summary_${successful.id}", prepared.first().id)
            assertTrue(prepared.none { it.id == invalid.id })
        }
    }

    @Test
    fun `blank successful Compact still excludes every older ancestor`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { emptyList() },
        )
        val old = message("old", null, 0, Participant.USER)
        val compact = message(
            "${Constants.COMPACT_MSG_PREFIX}blank",
            old.id,
            1,
        ).copy(text = "")
        val latest = message("latest", compact.id, 2, Participant.USER)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = latest.id,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(old, compact, latest),
            ),
        )

        assertEquals(listOf(compact.id, latest.id), path.messages.map { it.id })
        assertTrue(path.messages.none { it.id == old.id })
    }

    @Test
    fun `stopped run remains an assistant before queued guidance in first openai request`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { emptyList() },
        )
        val oldUser = message("old-user", null, 0, Participant.USER)
        val stoppedModel = message("stopped-model", oldUser.id, 1)
            .copy(text = "partial answer", status = MessageStatus.STOPPED, runId = "old-run")
        val queuedUser = message("queued-user", stoppedModel.id, 0, Participant.USER)
            .copy(text = "first guidance\n\nsecond guidance", runId = "fresh-run")
        val placeholder = message("placeholder", queuedUser.id, 1)
            .copy(text = "", status = MessageStatus.SENDING, runId = "fresh-run")

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = placeholder.parentId,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(oldUser, stoppedModel, queuedUser, placeholder),
            ),
        )

        assertEquals(listOf(oldUser.id, stoppedModel.id, queuedUser.id), path.messages.map { it.id })
        assertEquals(Participant.MODEL, path.messages[1].participant)
        assertTrue(path.messages[1].text.startsWith("partial answer"))
        assertTrue(path.messages[1].text.contains("<generation_interrupted reason=\"stopped\" />"))
        val projected = projectGenerationInputMessages(
            messages = path.messages,
            includeImages = true,
            userPrepend = path.providerConfig.userPrepend,
            userPostpend = path.providerConfig.userPostpend,
        )
        val wire = convertToOpenAiMessages(
            prepareMessages(projected, path.providerConfig.maxContextWindow),
            base64Files = Base64FileRegistry(),
        )
        val wireText = wire.flatMap { it.content.orEmpty() }.mapNotNull { it.text }.joinToString("\n")

        assertEquals(listOf("user", "assistant", "user"), wire.map { it.role })
        assertEquals(1, Regex(Regex.escape("partial answer")).findAll(wireText).count())
        assertEquals(1, Regex(Regex.escape("first guidance")).findAll(wireText).count())
        assertEquals(1, Regex(Regex.escape("second guidance")).findAll(wireText).count())
        assertEquals(
            1,
            Regex(Regex.escape("<generation_interrupted reason=\"stopped\" />")).findAll(wireText).count(),
        )
    }

    @Test
    fun `failed run sends the formatted error once on the same assistant turn`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val displayedError = "Displayed provider failure"
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { displayedError },
            toolDefinitions = { emptyList() },
        )
        val rawError = """Network error (403): {"balance":0.57,"code":"INSUFFICIENT_BALANCE"}"""
        val oldUser = message("old-user", null, 0, Participant.USER)
        val failedModel = message("failed-model", oldUser.id, 1)
            .copy(
                text = "",
                status = MessageStatus.ERROR,
                runId = "old-run",
                toolCallJson =
                    """[{"type":"error","content":"Network error (403): {\"balance\":0.57,\"code\":\"INSUFFICIENT_BALANCE\"}"}]""",
            )
        val followUp = message("follow-up", failedModel.id, 0, Participant.USER)
            .copy(text = "continue", runId = "fresh-run")
        val placeholder = message("placeholder", followUp.id, 1)
            .copy(text = "", status = MessageStatus.SENDING, runId = "fresh-run")

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = placeholder.parentId,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(oldUser, failedModel, followUp, placeholder),
            ),
        )

        val failed = path.messages.single { it.id == failedModel.id }
        assertEquals(Participant.MODEL, failed.participant)
        assertEquals(MessageStatus.SUCCESS, failed.status)
        assertEquals(1, Regex(Regex.escape(displayedError)).findAll(failed.text).count())
        assertFalse(failed.text.contains(rawError))
        val wire = convertToOpenAiMessages(
            prepareMessages(path.messages, path.providerConfig.maxContextWindow),
            base64Files = Base64FileRegistry(),
        )
        assertEquals(listOf("user", "assistant", "user"), wire.map { it.role })
        val wireText = wire.flatMap { it.content.orEmpty() }.mapNotNull { it.text }.joinToString("\n")
        assertEquals(1, Regex(Regex.escape(displayedError)).findAll(wireText).count())
        assertFalse(wireText.contains(rawError))
    }

    @Test
    fun `failed compact is not a request boundary`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { emptyList() },
        )
        val old = message("old", null, 0, participant = Participant.USER)
        val failed = message(
            "${Constants.COMPACT_MSG_PREFIX}failed",
            parentId = old.id,
            sequence = 1,
        ).copy(status = MessageStatus.ERROR)
        val user = message("user", failed.id, 2, Participant.USER)
        val model = message("model", user.id, 3)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = model.id,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(old, failed, user, model),
            ),
        )

        assertTrue(path.messages.any { it.id == old.id })
        assertTrue(path.messages.any { it.id == failed.id && it.text == failed.text })
        assertEquals("user", path.messages.single { it.id == user.id }.text)
    }

    @Test
    fun `production path delegates to the canonical selected context loader`() = runTest {
        val repository = mockk<ConversationRepository>()
        val contextLoader = mockk<DurableSelectedContextLoader>()
        val providerMessage = ChatMessage(
            id = "user",
            text = "question",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
        )
        coEvery { contextLoader.load(any()) } returns DurableSelectedContext(
            messages = listOf(providerMessage),
            entities = emptyList(),
        )
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            contextLoader = contextLoader,
            toolDefinitions = { emptyList() },
        )

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = "user",
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
            ),
        )

        assertEquals(listOf(providerMessage), path.messages)
        coVerify(exactly = 1) {
            contextLoader.load(match { request ->
                request.anchorMessageId == "user" &&
                    !request.followSelectedBranch
            })
        }
    }

    @Test
    fun `prompt cache key is scoped to the official OpenAI provider`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(
            conversations = repository,
            generationErrorFormatter = { it },
            toolDefinitions = { emptyList() },
        )
        val message = message("user", null, 0, Participant.USER)

        val official = builder.build(
            GenerationApiPathRequest(
                parentId = message.id,
                conversationId = "conversation",
                config = generationConfig(Constants.PROVIDER_OPENAI),
                context = GenerationContext(),
                loadedMessages = listOf(message),
            ),
        )
        val compatible = builder.build(
            GenerationApiPathRequest(
                parentId = message.id,
                conversationId = "conversation",
                config = generationConfig("OpenAI Compatible"),
                context = GenerationContext(),
                loadedMessages = listOf(message),
            ),
        )

        assertEquals("conversation", official.providerConfig.promptCacheKey)
        assertEquals(null, compatible.providerConfig.promptCacheKey)
    }

    private fun generationConfig(providerName: String = "provider") = GenerationConfig(
        providerName = providerName,
        modelId = "model-id",
        apiKey = "key",
        effectiveSystemPrompt = "system",
        codeExecutionEnabled = false,
        googleSearchEnabled = false,
        thinkingEnabled = false,
        baseUrl = null,
    )

    private fun toolDefinition() = ToolDefinition(
        function = ToolFunction(
            name = "test_tool",
            description = "Test tool",
            parameters = ToolParameters(
                properties = mapOf(
                    "value" to ToolProperty(
                        type = "string",
                        description = "Test value",
                    )
                ),
                required = listOf("value"),
            ),
        ),
    )

    private fun message(
        id: String,
        parentId: String?,
        sequence: Long,
        participant: Participant = Participant.MODEL,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "model-id",
        runId = "run",
        runSequence = sequence,
    )
}
