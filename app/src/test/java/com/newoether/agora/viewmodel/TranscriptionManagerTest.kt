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
import io.mockk.verify
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
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val captured = mutableListOf<ProviderConfig>()
        val capturedMessages = mutableListOf<List<ChatMessage>>()
        val provider = mockk<LlmProvider>()
        every { provider.generateResponse(capture(capturedMessages), capture(captured)) } returns flowOf(StreamEvent.TextChunk("Description"))
        val providerName = Constants.PROVIDER_OPENCODE_GO
        val manager = manager(mapOf(providerName to provider), conversations)
        coEvery { conversations.getMessage("user") } returns MessageEntity(
            id = "user", conversationId = "conversation", text = "", images = listOf(image.path),
            participant = Participant.USER, timestamp = 0, runId = "run", runSequence = 0,
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(AttachmentItem(type = "image", imageIndex = 0)))),
        )
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
                    targets = listOf(TranscriptionManager.TranscriptionTarget("user", image.path, 0, imageArtifact = image)),
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
            assertEquals(List(6) { mapOf(image.path to image) }, capturedMessages.map { it.single().imageArtifacts })
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

    @Test
    fun `legacy USER and MODEL metadata use actual artifacts without collection writes`() = runTest {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val manager = manager(emptyMap(), conversations)
        val user = source("user", listOf("content://legacy/image"), Participant.USER)
        val model = source("model", listOf("/private/generated.png"), Participant.MODEL)
        val userArtifact = image.copy(path = "/private/legacy.jpg", mimeType = "image/jpeg")
        val modelArtifact = image.copy(
            path = model.images.single(), mimeType = "image/jpeg", sizeBytes = 421L,
            width = 320, height = 240,
        )

        val targets = manager.collectTargets(snapshot(
            user, model, artifactsById = mapOf("user" to listOf(userArtifact), "model" to listOf(modelArtifact)),
        ))

        assertEquals(listOf("user", "model"), targets.map { it.messageId })
        assertEquals(user, targets[0].sourceMessage)
        assertEquals(userArtifact.path, targets[0].imagePath)
        assertEquals(model, targets[1].sourceMessage)
        val item = targets[1].preparedMetadata!!.items.single()
        assertEquals("generated.png", item.fileName)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(421L, item.fileSize)
        assertEquals(320, item.pixelWidth)
        assertEquals(240, item.pixelHeight)
        assertEquals(listOf(modelArtifact), item.imageArtifacts)
        coVerify(exactly = 0) { conversations.getMessage(any()) }
        coVerify(exactly = 0) { conversations.upsertMessage(any()) }
    }

    @Test
    fun `cached history is retained while latest USER refreshes every PDF page in order`() {
        val manager = manager(emptyMap())
        val cached = AttachmentMeta(listOf(AttachmentItem(type = "image", imageIndex = 0, transcription = "Cached")))
        val older = source("older", listOf("/private/older.jpg"), Participant.USER, cached)
        val model = source("model", listOf("/private/model.jpg"), Participant.MODEL, cached)
        val latest = source(
            "latest", listOf("/private/page1.jpg", "/private/page2.jpg"), Participant.USER,
            AttachmentMeta(listOf(AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2, transcription = "Old pages"))),
        )
        val tool = source("${Constants.TOOL_MSG_PREFIX}round", listOf(image.path), Participant.MODEL)
        val result = source("${Constants.RESULT_MSG_PREFIX}round", listOf(image.path), Participant.USER)

        val targets = manager.collectTargets(snapshot(older, model, latest, tool, result))

        assertEquals(listOf("latest", "latest"), targets.map { it.messageId })
        assertEquals(latest.images, targets.map { it.imagePath })
        assertEquals(listOf(0, 0), targets.map { it.metaItemIndex })
        assertEquals(latest.images, targets[0].preparedMetadata!!.items.single().imageArtifacts.map { it.path })
    }

    @Test
    fun `persistence checks all rows before writes and preserves current non-image fields`() = runTest {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val manager = manager(emptyMap(), conversations)
        val user = source(
            "user", listOf("/private/page1.jpg", "/private/page2.jpg"), Participant.USER,
            AttachmentMeta(listOf(AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2))),
        )
        val model = source("model", listOf("/private/model.png"), Participant.MODEL)
        val targets = manager.collectTargets(snapshot(user, model))
        val current = listOf(
            user.copy(text = "Edited concurrently", tokenCount = 42, status = MessageStatus.STOPPED, consumedAtPass = 3),
            model.copy(text = "New model text", thoughts = "New thoughts", inputTokenCount = 17),
        ).associateBy { it.id }
        val events = mutableListOf<String>()
        val persisted = mutableListOf<MessageEntity>()
        var inTransaction = false
        coEvery { conversations.withProviderContextSnapshot<Unit>(any()) } coAnswers {
            assertEquals(false, inTransaction)
            inTransaction = true
            try { firstArg<suspend () -> Unit>().invoke() } finally { inTransaction = false }
        }
        coEvery { conversations.getMessage(any()) } coAnswers {
            assertEquals(true, inTransaction)
            val id = firstArg<String>()
            events += "read:$id"
            current[id]
        }
        coEvery { conversations.upsertMessage(any()) } coAnswers {
            assertEquals(true, inTransaction)
            val row = firstArg<MessageEntity>()
            events += "write:${row.id}"
            persisted += row
        }

        manager.persistTranscriptions("conversation", targets.mapIndexed { index, target -> target to "Description $index" })

        assertEquals(false, inTransaction)
        assertEquals(listOf("read:user", "read:model", "write:user", "write:model"), events)
        persisted.forEach { updated ->
            assertEquals(current.getValue(updated.id).copy(attachmentMeta = updated.attachmentMeta), updated)
        }
        val userMeta = Json.decodeFromString<AttachmentMeta>(persisted[0].attachmentMeta!!)
        assertEquals("[Page 1]\nDescription 0\n\n[Page 2]\nDescription 1", userMeta.items.single().transcription)
        assertEquals(user.images, userMeta.items.single().imageArtifacts.map { it.path })
        val modelMeta = Json.decodeFromString<AttachmentMeta>(persisted[1].attachmentMeta!!)
        assertEquals("Description 2", modelMeta.items.single().transcription)
        assertEquals(listOf(targets.last().imageArtifact), modelMeta.items.single().imageArtifacts)
    }

    @Test
    fun `deleted replaced reordered or changed source identity prevents every write`() = runTest {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val manager = manager(emptyMap(), conversations)
        val user = source("user", listOf(image.path), Participant.USER)
        val model = source(
            "model", listOf("/private/first.jpg", "/private/second.jpg"), Participant.MODEL,
            AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0), AttachmentItem(type = "image", imageIndex = 1),
            )),
        )
        val results = manager.collectTargets(snapshot(user, model)).map { it to "Description" }
        var current: MessageEntity? = model
        coEvery { conversations.getMessage("user") } returns user
        coEvery { conversations.getMessage("model") } coAnswers { current }
        val changedMeta = Json.decodeFromString<AttachmentMeta>(model.attachmentMeta!!).let { meta ->
            meta.copy(items = meta.items.map { it.copy(transcription = "Concurrent transcription") })
        }
        val changes = listOf(
            "deleted" to null,
            "reordered" to model.copy(images = model.images.reversed()),
            "replaced" to model.copy(images = listOf("/private/replacement.jpg", model.images[1])),
            "removed" to model.copy(images = model.images.take(1)),
            "metadata" to model.copy(attachmentMeta = Json.encodeToString(changedMeta)),
            "conversation" to model.copy(conversationId = "other"),
            "id" to model.copy(id = "other"),
            "parent" to model.copy(parentId = "other"),
            "participant" to model.copy(participant = Participant.USER),
            "timestamp" to model.copy(timestamp = model.timestamp + 1),
            "run" to model.copy(runId = "replacement-run"),
            "sequence" to model.copy(runSequence = model.runSequence + 1),
        )
        for ((label, row) in changes) {
            current = row
            val failure = runCatching { manager.persistTranscriptions("conversation", results) }.exceptionOrNull()
            assertEquals(label, true, failure is IllegalStateException)
        }
        coVerify(exactly = 0) { conversations.upsertMessage(any()) }
    }

    @Test
    fun `partial pages reordered results or changed artifact cannot persist a description`() = runTest {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val manager = manager(emptyMap(), conversations)
        val user = source(
            "user", listOf("/private/page1.jpg", "/private/page2.jpg"), Participant.USER,
            AttachmentMeta(listOf(AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2))),
        )
        val targets = manager.collectTargets(snapshot(user))
        coEvery { conversations.getMessage("user") } returns user
        val valid = targets.map { it to "Description" }
        val changed = targets[0].copy(imageArtifact = targets[0].imageArtifact!!.copy(path = "/private/replaced.jpg"))
        val unavailable = targets[0].copy(imageArtifact = targets[0].imageArtifact!!.copy(unavailable = true))
        for (results in listOf(
            valid.take(1), valid.reversed(),
            listOf(changed to "Changed", valid[1]),
            listOf(unavailable to "Unavailable", valid[1]),
            listOf(targets[0] to " ", valid[1]),
        )) {
            val failure = runCatching { manager.persistTranscriptions("conversation", results) }.exceptionOrNull()
            assertEquals(true, failure is IllegalStateException)
        }
        coVerify(exactly = 0) { conversations.upsertMessage(any()) }
    }

    @Test
    fun `repeated paths silently skip unavailable slots and preserve valid metadata in both orders`() = runTest {
        for (unavailableIndex in listOf(0, 1)) {
            val conversations = mockk<ConversationRepository>(relaxed = true)
            val manager = manager(emptyMap(), conversations)
            val artifacts = List(2) { index ->
                image.copy(policyVersion = index + 3, unavailable = index == unavailableIndex)
            }
            val metadata = AttachmentMeta(artifacts.mapIndexed { index, artifact ->
                AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(artifact))
            })
            val user = source("duplicate", List(2) { image.path }, Participant.USER, metadata)
            val targets = manager.collectTargets(snapshot(user, artifactsById = mapOf(user.id to artifacts)))
            assertEquals(listOf(1 - unavailableIndex), targets.map { it.metaItemIndex })
            assertEquals(listOf(artifacts[1 - unavailableIndex]), targets.map { it.imageArtifact })
            val prepared = targets.single().preparedMetadata!!.items
            assertEquals(artifacts, prepared.flatMap { it.imageArtifacts })
            assertEquals(true, prepared[unavailableIndex].unavailable)
            coEvery { conversations.getMessage(user.id) } returns user
            val persisted = mutableListOf<MessageEntity>()
            coEvery { conversations.upsertMessage(capture(persisted)) } returns Unit
            manager.persistTranscriptions("conversation", targets.map { it to "Description" })
            val saved = Json.decodeFromString<AttachmentMeta>(persisted.single().attachmentMeta!!)
            assertNull(saved.items[unavailableIndex].transcription)
            assertEquals("Description", saved.items[1 - unavailableIndex].transcription)
            assertEquals(user.images, persisted.single().images)
        }
    }

    @Test
    fun `unavailable tool images and all unavailable targets return silently before progress or provider lookup`() = runTest {
        val provider = mockk<LlmProvider>()
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val manager = manager(mapOf("transcriber" to provider), conversations)
        val bad = image.copy(unavailable = true)
        assertNull(manager.describeImageWithProgress(
            image = bad, ctx = context("absent"), generationJob = null,
            conversationId = "conversation", runId = "run", pass = 0, modelMessageId = "assistant",
            onProgress = { error("Unavailable image must remain silent") },
        ))
        val (segments, failure) = manager.transcribe(
            targets = listOf(TranscriptionManager.TranscriptionTarget("user", bad.path, 0, bad)),
            conversationId = "conversation", runId = "run", pass = 0, providerName = "absent",
            modelId = "vision-model", apiKey = "key", baseUrl = null, prompt = "Describe",
            generationJob = null, modelMessageId = "assistant", startTime = 0L,
            onProgress = { error("Unavailable image must not create a placeholder") },
        )
        assertEquals(emptyList<Any>(), segments)
        assertNull(failure)
        verify(exactly = 0) { provider.generateResponse(any(), any()) }
        coVerify(exactly = 0) { conversations.getMessage(any()) }
        coVerify(exactly = 0) { conversations.upsertMessage(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `mixed PDF transcribes only readable pages and persists original page numbers`() = runTest {
        val conversations = mockk<ConversationRepository>(relaxed = true)
        val provider = mockk<LlmProvider>()
        val requests = mutableListOf<List<ChatMessage>>()
        every { provider.generateResponse(capture(requests), any()) } returns flowOf(StreamEvent.TextChunk("Visible page"))
        val manager = manager(mapOf("transcriber" to provider), conversations)
        val bad = image.copy(unavailable = true)
        val artifacts = listOf(bad, image, bad)
        val user = source("pages", List(3) { image.path }, Participant.USER, AttachmentMeta(listOf(
            AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 3, imageArtifacts = artifacts),
        )))
        val targets = manager.collectTargets(snapshot(user, artifactsById = mapOf(user.id to artifacts)))
        assertEquals(listOf(1), targets.map { it.imageIndex })
        coEvery { conversations.getMessage(user.id) } returns user
        val saved = mutableListOf<MessageEntity>()
        coEvery { conversations.upsertMessage(capture(saved)) } returns Unit
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        try {
            val (segments, failure) = manager.transcribe(
                targets = listOf(targets.single().copy(imageArtifact = bad)) + targets,
                conversationId = "conversation", runId = "run", pass = 0, providerName = "transcriber",
                modelId = "vision-model", apiKey = "key", baseUrl = null, prompt = "Describe",
                generationJob = null, modelMessageId = "assistant", startTime = 0L, onProgress = {},
            )
            assertNull(failure)
            assertEquals(listOf("Visible page"), segments.map { it.content })
            assertEquals(listOf(image.path), requests.single().single().images)
            val metadata = Json.decodeFromString<AttachmentMeta>(saved.single().attachmentMeta!!)
            assertEquals("[Page 2]\nVisible page", metadata.items.single().transcription)
            assertEquals(artifacts, metadata.items.single().imageArtifacts)
            assertEquals(user.images, saved.single().images)
        } finally {
            unmockkStatic(Looper::class)
            Dispatchers.resetMain()
        }
    }

    private fun source(
        id: String,
        images: List<String>,
        participant: Participant,
        metadata: AttachmentMeta? = null,
    ) = MessageEntity(
        id = id, conversationId = "conversation", parentId = "parent", text = "Original",
        images = images, participant = participant, timestamp = 10L, runId = "run", runSequence = 0L,
        attachmentMeta = metadata?.let { Json.encodeToString(it) },
    )

    private fun snapshot(
        vararg rows: MessageEntity,
        artifactsById: Map<String, List<ToolImageAttachment>> = emptyMap(),
    ) = DurableSelectedContext(
        entities = rows.toList(),
        messages = rows.map { row ->
            val artifacts = artifactsById[row.id] ?: row.images.map { image.copy(path = it) }
            val metadata = row.attachmentMeta?.let { Json.decodeFromString<AttachmentMeta>(it) }
            val projected = metadata?.copy(items = metadata.items.map { item ->
                val start = item.imageIndex
                if (start == null || item.storage.isLocalSandbox || item.type !in setOf("image", "pdf", "video")) item
                else {
                    val count = if (item.type == "image") 1 else item.pageCount ?: 1
                    item.copy(imageArtifacts = artifacts.drop(start).take(count))
                }
            }) ?: AttachmentMeta(artifacts.mapIndexed { index, artifact ->
                AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(artifact))
            })
            ChatMessage(
                id = row.id, text = row.text, participant = row.participant,
                images = artifacts.map { it.path }, imageArtifacts = artifacts.associateBy { it.path },
                attachmentMeta = projected,
            )
        },
    )

    private fun manager(
        providers: Map<String, LlmProvider>,
        conversations: ConversationRepository = mockk(relaxed = true),
    ): TranscriptionManager {
        coEvery { conversations.withProviderContextSnapshot<Unit>(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
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
