package com.newoether.agora.viewmodel
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleGeneratorTest {
    @Test
    fun titleRequestsUseTheirOwnProvidersCacheSelection() = runTest {
        val custom = CustomProviderConfig(
            "Relay", CustomEndpointProtocol.ANTHROPIC,
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            anthropicCacheEnabled = false, anthropicCacheTtl = "5m",
        )
        val settings = mockk<SettingsRepository>(relaxed = true)
        val dao = mockk<ChatDao>(relaxed = true)
        val conversations = ConversationRepository(dao, database = null)
        val registry = mockk<ProviderRegistry>(relaxed = true)
        val provider = mockk<LlmProvider>()
        val captured = mutableListOf<ProviderConfig>()
        val user = MessageEntity(
            id = "user", conversationId = "conversation", text = "Hello",
            participant = Participant.USER, status = MessageStatus.SUCCESS,
            timestamp = 1L, runId = "run", runSequence = 0L,
        )
        coEvery { dao.getConversation("conversation") } returns ChatEntity("conversation", "Old")
        coEvery { dao.getMessageContextTopology("conversation") } returns listOf(
            MessageContextTopology(
                user.id, user.conversationId, null, user.status, user.participant,
                user.timestamp, modelName = null, runId = user.runId,
                runSequence = user.runSequence, consumedAtPass = null,
            ),
        )
        coEvery { dao.getMessage("user") } returns user
        coEvery { dao.updateConversationTitleIfUnchanged(any(), any(), any(), any()) } returns 1
        every { settings.titleGenerationPrompt } returns MutableStateFlow("Title prompt")
        every { settings.anthropicCacheEnabled } returns MutableStateFlow(true)
        every { settings.anthropicCacheTtl } returns MutableStateFlow("1h")
        every { settings.customProviders } returns MutableStateFlow(listOf(custom))
        every { registry.canonicalModelId(any()) } answers { firstArg() }
        every { registry.isConfigured(any(), any()) } returns true
        every { registry.getInstanceOrNull(any()) } returns provider
        every { registry.getEffectiveBaseUrl(any()) } returns null
        every { provider.generateResponse(any(), capture(captured)) } returns flowOf(StreamEvent.TextChunk("Title"))
        val generator = ConversationTitleGenerator(conversations, settings, registry)
        for (name in listOf("Anthropic", custom.providerId)) {
            every { settings.titleGenerationModel } returns MutableStateFlow("$name:model")
            every { registry.providerForModel("$name:model") } returns name
            assertEquals(ConversationTitleGenerator.Result.Success("Title"), generator.generateAndPersist("conversation"))
        }
        assertEquals(listOf(true, false), captured.map { it.anthropicCacheEnabled })
        assertEquals(listOf("1h", "5m"), captured.map { it.anthropicCacheTtl })
    }

    @Test
    fun titleRequestCarriesTheConversationIdForMunito() = runTest {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val dao = mockk<ChatDao>(relaxed = true)
        val conversations = ConversationRepository(dao, database = null)
        val registry = mockk<ProviderRegistry>(relaxed = true)
        val provider = mockk<LlmProvider>()
        val captured = mutableListOf<ProviderConfig>()
        val user = MessageEntity(
            id = "user", conversationId = "conversation", text = "Hello",
            participant = Participant.USER, status = MessageStatus.SUCCESS,
            timestamp = 1L, runId = "run", runSequence = 0L,
        )
        coEvery { dao.getConversation("conversation") } returns ChatEntity("conversation", "Old")
        coEvery { dao.getMessageContextTopology("conversation") } returns listOf(
            MessageContextTopology(
                user.id, user.conversationId, null, user.status, user.participant,
                user.timestamp, modelName = null, runId = user.runId,
                runSequence = user.runSequence, consumedAtPass = null,
            ),
        )
        coEvery { dao.getMessage("user") } returns user
        coEvery { dao.updateConversationTitleIfUnchanged(any(), any(), any(), any()) } returns 1
        every { settings.titleGenerationModel } returns MutableStateFlow("${Constants.PROVIDER_MUNITO}:model")
        every { settings.titleGenerationPrompt } returns MutableStateFlow("Title prompt")
        every { settings.anthropicCacheEnabled } returns MutableStateFlow(false)
        every { settings.anthropicCacheTtl } returns MutableStateFlow("5m")
        every { settings.customProviders } returns MutableStateFlow(emptyList())
        every { registry.canonicalModelId(any()) } answers { firstArg() }
        every { registry.providerForModel(any()) } returns Constants.PROVIDER_MUNITO
        every { registry.isConfigured(any(), any()) } returns true
        every { registry.getInstanceOrNull(any()) } returns provider
        every { registry.getEffectiveBaseUrl(any()) } returns null
        every { provider.generateResponse(any(), capture(captured)) } returns flowOf(StreamEvent.TextChunk("Title"))

        val generator = ConversationTitleGenerator(conversations, settings, registry)
        assertEquals(ConversationTitleGenerator.Result.Success("Title"), generator.generateAndPersist("conversation"))
        assertEquals("conversation", captured.single().sessionId)
    }

    @Test
    fun titleResetRetryDiscardsTheFailedPrefixAndErrorBeforePersisting() = runTest {
        val settings = mockk<SettingsRepository>(relaxed = true)
        val dao = mockk<ChatDao>(relaxed = true)
        val conversations = ConversationRepository(dao, database = null)
        val registry = mockk<ProviderRegistry>(relaxed = true)
        val provider = mockk<LlmProvider>()
        val user = MessageEntity(
            id = "user", conversationId = "conversation", text = "Hello",
            participant = Participant.USER, status = MessageStatus.SUCCESS,
            timestamp = 1L, runId = "run", runSequence = 0L,
        )
        coEvery { dao.getConversation("conversation") } returns ChatEntity("conversation", "Old")
        coEvery { dao.getMessageContextTopology("conversation") } returns listOf(
            MessageContextTopology(
                user.id, user.conversationId, null, user.status, user.participant,
                user.timestamp, modelName = null, runId = user.runId,
                runSequence = user.runSequence, consumedAtPass = null,
            ),
        )
        coEvery { dao.getMessage("user") } returns user
        coEvery { dao.updateConversationTitleIfUnchanged(any(), any(), any(), any()) } returns 1
        every { settings.titleGenerationModel } returns MutableStateFlow("Anthropic:model")
        every { settings.titleGenerationPrompt } returns MutableStateFlow("Title prompt")
        every { settings.anthropicCacheEnabled } returns MutableStateFlow(true)
        every { settings.anthropicCacheTtl } returns MutableStateFlow("1h")
        every { settings.customProviders } returns MutableStateFlow(emptyList())
        every { registry.canonicalModelId(any()) } answers { firstArg() }
        every { registry.providerForModel(any()) } returns "Anthropic"
        every { registry.isConfigured(any(), any()) } returns true
        every { registry.getInstanceOrNull(any()) } returns provider
        every { registry.getEffectiveBaseUrl(any()) } returns null
        every { provider.generateResponse(any(), any()) } returns flowOf(
            StreamEvent.TextChunk("Failed attempt prefix "),
            StreamEvent.Error(GenerationError.SseParse("malformed", "Invalid response")),
            StreamEvent.Retrying(attempt = 1, maxAttempts = 5, resetCurrentAttempt = true),
            StreamEvent.TextChunk("  Recovered\n"),
            StreamEvent.TextChunk("title  "),
        )
        val generator = ConversationTitleGenerator(conversations, settings, registry)

        val result = generator.generateAndPersist("conversation")

        assertEquals(ConversationTitleGenerator.Result.Success("Recovered title"), result)
        coVerify(exactly = 1) {
            dao.updateConversationTitleIfUnchanged("conversation", "Old", "Recovered title", any())
        }
    }

    @Test
    fun initialTitleCollapsesWhitespaceAndFitsThirtyTwoCodePoints() {
        val prompt = "  " + "a".repeat(20) + "\n" + "b".repeat(20) + "  "

        val title = initialConversationTitle(prompt, fallback = "New Chat")

        assertEquals("a".repeat(20) + " " + "b".repeat(10) + "…", title)
        assertEquals(32, title.codePointCount(0, title.length))
    }

    @Test
    fun initialTitleDoesNotSplitSupplementaryCharacters() {
        val emoji = "\uD83D\uDE00"

        val title = initialConversationTitle(emoji.repeat(40), fallback = "New Chat")

        assertEquals(emoji.repeat(31) + "…", title)
        assertEquals(32, title.codePointCount(0, title.length))
    }

    @Test
    fun initialTitleFallsBackForBlankPrompt() {
        assertEquals("New Chat", initialConversationTitle(" \n\t ", fallback = "New Chat"))
    }

    @Test
    fun fallbackTitleCollapsesWhitespaceAndTruncates() {
        val response = "  First line\n\nSecond\tline  " + "x".repeat(80)

        val title = fallbackConversationTitle(response)

        assertEquals(60, title.length)
        assertEquals("First line Second line " + "x".repeat(37), title)
    }

    @Test
    fun fallbackTitleKeepsEmptyResponseEmpty() {
        assertEquals("", fallbackConversationTitle(" \n\t "))
    }

    @Test
    fun attachmentOnlyUserProducesTextualTitleSource() {
        val source = titleSourceText(
            ChatMessage(
                text = "",
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "requirements.pdf",
                            mimeType = "application/pdf",
                            textContent = "Release constraints",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(source.contains("--- File: requirements.pdf ---"))
        assertTrue(source.contains("Release constraints"))
    }

    @Test
    fun projectedAttachmentTextIsNotDuplicated() {
        val projectedText = "--- File: requirements.pdf ---\nRelease constraints"
        val source = titleSourceText(
            ChatMessage(
                text = projectedText,
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "requirements.pdf",
                            textContent = "Release constraints",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(projectedText, source)
    }

    @Test
    fun imageOnlyUserProducesStableMetadataTitleSource() {
        val source = titleSourceText(
            ChatMessage(
                text = "",
                participant = Participant.USER,
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "image",
                            fileName = "architecture.png",
                            mimeType = "image/png",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(source.contains("architecture.png"))
        assertTrue(source.contains("image/png"))
    }
}
