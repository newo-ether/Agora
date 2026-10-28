package com.newoether.agora.viewmodel

import android.content.Context
import android.os.Looper
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionManagerTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun bothTranscriptionPathsForwardTheFrozenCacheFields() = runTest {
        val captured = mutableListOf<ProviderConfig>()
        val provider = mockk<LlmProvider>()
        every { provider.generateResponse(any(), capture(captured)) } returns flowOf(StreamEvent.TextChunk("Description"))
        val providerName = Constants.PROVIDER_OPENCODE_GO
        val manager = manager(mapOf(providerName to provider))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        try {
            for ((enabled, ttl) in listOf(false to "5m", true to "5m", true to "1h")) {
                manager.describeImageWithProgress(
                    image = image,
                    ctx = context(providerName).copy(
                        transcriptionAnthropicCacheEnabled = enabled,
                        transcriptionAnthropicCacheTtl = ttl,
                    ),
                    generationJob = null, conversationId = "conversation", runId = "run",
                    pass = 0, modelMessageId = "assistant", onProgress = {},
                )
                manager.transcribe(
                    targets = listOf(TranscriptionManager.TranscriptionTarget("user", image.path, 0)),
                    conversationId = "conversation", runId = "run", pass = 0,
                    providerName = providerName, modelId = "vision-model", apiKey = "",
                    baseUrl = null, prompt = "Describe", generationJob = null,
                    modelMessageId = "assistant", startTime = 0L,
                    anthropicCacheEnabled = enabled, anthropicCacheTtl = ttl, onProgress = {},
                )
                assertEquals(listOf(enabled, enabled), captured.takeLast(2).map { it.anthropicCacheEnabled })
                assertEquals(listOf(ttl, ttl), captured.takeLast(2).map { it.anthropicCacheTtl })
                assertEquals(listOf("conversation", "conversation"), captured.takeLast(2).map { it.sessionId })
            }
            assertEquals(6, captured.size)
        } finally {
            unmockkStatic(Looper::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun bothTranscriptionPathsCarryTheSessionIdForMunito() = runTest {
        val captured = mutableListOf<ProviderConfig>()
        val provider = mockk<LlmProvider>()
        every { provider.generateResponse(any(), capture(captured)) } returns flowOf(StreamEvent.TextChunk("Description"))
        val providerName = Constants.PROVIDER_MUNITO
        val manager = manager(mapOf(providerName to provider))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        try {
            manager.describeImageWithProgress(
                image = image,
                ctx = context(providerName),
                generationJob = null, conversationId = "conversation", runId = "run",
                pass = 0, modelMessageId = "assistant", onProgress = {},
            )
            manager.transcribe(
                targets = listOf(TranscriptionManager.TranscriptionTarget("user", image.path, 0)),
                conversationId = "conversation", runId = "run", pass = 0,
                providerName = providerName, modelId = "vision-model", apiKey = "",
                baseUrl = null, prompt = "Describe", generationJob = null,
                modelMessageId = "assistant", startTime = 0L,
                anthropicCacheEnabled = false, anthropicCacheTtl = "5m", onProgress = {},
            )
            assertEquals(listOf("conversation", "conversation"), captured.map { it.sessionId })
        } finally {
            unmockkStatic(Looper::class)
            Dispatchers.resetMain()
        }
    }
    private val image = ToolImageAttachment(
        path = "/private/tool.png",
        mimeType = "image/png",
        sizeBytes = 1L,
        width = 1,
        height = 1,
        sha256 = "sha",
    )

    @Test
    fun `describeImage streams chunks and returns the trimmed description`() = runTest {
        val manager = manager(
            providers = mapOf(
                "transcriber" to fakeProvider(
                    events = listOf(
                        StreamEvent.TextChunk("A cat "),
                        StreamEvent.TextChunk("sitting."),
                    ),
                ),
            ),
        )
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "transcriber"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertEquals("A cat sitting.", description)
        // The transcribing state is announced before the first chunk; the block never starts
        // empty.
        assertEquals(listOf("Transcribing…", "A cat ", "A cat sitting."), progress)
    }

    @Test
    fun `describeImage reset retry immediately clears failed progress before replacement`() = runTest {
        val progress = mutableListOf<String>()
        val provider = mockk<LlmProvider>()
        every { provider.generateResponse(any(), any()) } returns flow {
            emit(StreamEvent.TextChunk("Failed prefix"))
            emit(StreamEvent.Error(GenerationError.SseParse("malformed", "Invalid response")))
            emit(StreamEvent.Retrying(attempt = 1, maxAttempts = 5, resetCurrentAttempt = true))
            assertEquals(listOf("Transcribing…", "Failed prefix", "Transcribing…"), progress)
            emit(StreamEvent.TextChunk("A recovered "))
            emit(StreamEvent.TextChunk("description. "))
        }
        val manager = manager(mapOf("transcriber" to provider))

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "transcriber"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertEquals("A recovered description.", description)
        assertEquals(
            listOf(
                "Transcribing…", "Failed prefix", "Transcribing…",
                "A recovered ", "A recovered description. ",
            ),
            progress,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `attachment reset retry keeps completed images and immediately publishes current placeholder`() = runTest {
        val progress = mutableListOf<ChatMessage>()
        val provider = mockk<LlmProvider>()
        var imageRequest = 0
        every { provider.generateResponse(any(), any()) } answers {
            imageRequest++
            if (imageRequest == 1) {
                flowOf(StreamEvent.TextChunk("Completed first image"))
            } else {
                flow {
                    emit(StreamEvent.TextChunk("Failed second prefix"))
                    emit(StreamEvent.Error(GenerationError.SseParse("malformed", "Invalid response")))
                    emit(StreamEvent.Retrying(attempt = 1, maxAttempts = 5, resetCurrentAttempt = true))
                    assertEquals(5, progress.size)
                    assertEquals(
                        listOf("Completed first image", "Transcribing…", ""),
                        progress.last().segments.orEmpty().map { it.content },
                    )
                    assertEquals(
                        listOf("transcription", "transcription", "answer"),
                        progress.last().segments.orEmpty().map { it.type },
                    )
                    assertEquals(MessageStatus.TRANSCRIBING, progress.last().status)
                    emit(StreamEvent.TextChunk("Recovered second "))
                    emit(StreamEvent.TextChunk("image "))
                }
            }
        }
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val originalMeta = AttachmentMeta(
            items = listOf(
                AttachmentItem(type = "image", fileName = "first.png", imageIndex = 0),
                AttachmentItem(type = "image", fileName = "second.png", imageIndex = 1),
            ),
        )
        val user = MessageEntity(
            id = "user", conversationId = "conversation", text = "",
            images = listOf(image.path, "/private/second.png"),
            participant = Participant.USER, status = MessageStatus.SUCCESS,
            timestamp = 1L, runId = "run", runSequence = 0L,
            attachmentMeta = Json.encodeToString(AttachmentMeta.serializer(), originalMeta),
        )
        coEvery { conversations.getMessage("assistant") } returns null
        coEvery { conversations.getMessage("user") } returns user
        val persisted = mutableListOf<MessageEntity>()
        coEvery { conversations.upsertMessage(capture(persisted)) } returns Unit
        val manager = manager(mapOf("transcriber" to provider), conversations)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        try {
            val (segments, error) = manager.transcribe(
                targets = listOf(
                    TranscriptionManager.TranscriptionTarget("user", image.path, 0),
                    TranscriptionManager.TranscriptionTarget("user", "/private/second.png", 1),
                ),
                conversationId = "conversation", runId = "run", pass = 2,
                providerName = "transcriber", modelId = "vision-model", apiKey = "key",
                baseUrl = null, prompt = "Describe", generationJob = null,
                modelMessageId = "assistant", startTime = 0L,
                onProgress = { progress += it },
            )

            assertNull(error)
            assertEquals(2, imageRequest)
            assertEquals(
                listOf("Completed first image", "Recovered second image"),
                segments.map { it.content },
            )
            assertEquals(
                listOf(
                    listOf("Transcribing…", ""),
                    listOf("Completed first image", ""),
                    listOf("Completed first image", "Transcribing…", ""),
                    listOf("Completed first image", "Failed second prefix", ""),
                    listOf("Completed first image", "Transcribing…", ""),
                    listOf("Completed first image", "Recovered second ", ""),
                    listOf("Completed first image", "Recovered second image ", ""),
                ),
                progress.map { message -> message.segments.orEmpty().map { it.content } },
            )
            coVerify(exactly = 1) { conversations.upsertMessage(any()) }
            val updated = persisted.single()
            val expectedMeta = originalMeta.copy(
                items = listOf(
                    originalMeta.items[0].copy(transcription = "Completed first image"),
                    originalMeta.items[1].copy(transcription = "Recovered second image"),
                ),
            )
            assertEquals(
                user.copy(attachmentMeta = Json.encodeToString(AttachmentMeta.serializer(), expectedMeta)),
                updated,
            )
        } finally {
            unmockkStatic(Looper::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `describeImage fails open to null on stream errors but emits a failure notice`() = runTest {
        val manager = manager(
            providers = mapOf(
                "transcriber" to fakeProvider(
                    events = listOf(
                        StreamEvent.TextChunk("partial"),
                        StreamEvent.Error(GenerationError.Network(statusCode = 500, message = "boom")),
                    ),
                ),
            ),
        )
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "transcriber"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertNull(description)
        assertEquals(
            listOf("Transcribing…", "partial", "Image transcription failed"),
            progress,
        )
    }

    @Test
    fun `describeImage is fail closed on a missing provider but emits a failure notice`() = runTest {
        val manager = manager(providers = emptyMap())
        val progress = mutableListOf<String>()

        val description = manager.describeImageWithProgress(
            image = image,
            ctx = context(providerName = "absent"),
            generationJob = null,
            conversationId = "conversation",
            runId = "run",
            pass = 2,
            modelMessageId = "assistant",
            onProgress = { progress += it },
        )

        assertNull(description)
        assertEquals(
            listOf("Transcribing…", "Image transcription failed"),
            progress,
        )
    }

    private fun manager(
        providers: Map<String, LlmProvider>,
        conversations: ConversationRepository = mockk(relaxed = true),
    ): TranscriptionManager {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.transcription_ellipsis_single) } returns "Transcribing…"
        every { context.getString(R.string.transcription_ellipsis) } returns "Transcribing…"
        every { context.getString(R.string.transcription_label) } returns "Transcription"
        every { context.getString(R.string.generation_error_sse_parse) } returns "The server response could not be read."
        every { context.getString(R.string.transcription_progress, any<Int>(), any<Int>()) } returns "Transcribing"
        every {
            context.getString(R.string.generation_error_transcription, any<String>())
        } returns "Image transcription failed"
        return TranscriptionManager(
            providers = providers,
            conversations = conversations,
            context = context,
        )
    }

    private fun context(providerName: String) = GenerationContext(
        imageTranscriptionEnabled = true,
        transcriptionProviderName = providerName,
        transcriptionModelId = "vision-model",
        transcriptionApiKey = "key",
    )

    private fun fakeProvider(events: List<StreamEvent>): LlmProvider = object : LlmProvider {
        override val name: String = "fake"
        override val defaultBaseUrl: String = ""
        override fun generateResponse(
            messages: List<ChatMessage>,
            config: ProviderConfig,
        ): Flow<StreamEvent> = flowOf(*events.toTypedArray())
        override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
    }
}
