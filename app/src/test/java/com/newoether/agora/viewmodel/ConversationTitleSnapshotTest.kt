package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleSnapshotTest {
    @Test
    fun firstUserMessageIsEnoughBeforeAnyAssistantReplyExists() = runTest {
        val fixture = Fixture(listOf(message("user", text = "Existing text", role = Participant.USER)))

        assertEquals(
            ConversationTitleGenerator.Result.Success("Generated"),
            fixture.generator.generateAndPersist("conversation"),
        )
        assertTrue(fixture.prompts.single().contains("\n\nExisting text\n\n"))
        coVerify(exactly = 1) { fixture.dao.getMessage("user") }
    }

    @Test
    fun assistantOnlyConversationMakesNoProviderCall() = runTest {
        val fixture = Fixture(listOf(message("answer", text = "Assistant text")))

        assertEquals(
            ConversationTitleGenerator.Result.Failure("Conversation has no usable text"),
            fixture.generator.generateAndPersist("conversation"),
        )
        assertTrue(fixture.prompts.isEmpty())
        coVerify(exactly = 0) { fixture.dao.getMessage(any()) }
    }

    @Test
    fun firstUserInputIsFrozenBeforeSettingsAndNotificationWaits() = runTest {
        val user = message("user", text = "Tap input", role = Participant.USER)
        val model = message("answer", parent = user.id, text = "Older checkpoint")
        val fixture = Fixture(listOf(user, model))
        val originalSnapshot = live(model, "Partial at tap")
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.settings.awaitInitialLoad() } coAnswers {
            captured.complete(Unit)
            release.await()
        }
        val pending = async {
            fixture.generator.generateAndPersist("conversation", originalSnapshot) {
                fixture.messages = listOf(user.copy(text = "After notice"), model.copy(text = "Final"))
            }
        }
        captured.await()
        fixture.messages = listOf(user.copy(text = "Later input"), model.copy(text = "Later checkpoint"))
        release.complete(Unit)

        assertEquals(ConversationTitleGenerator.Result.Success("Generated"), pending.await())
        val prompt = fixture.prompts.single()
        assertTrue(prompt.contains("\n\nTap input\n\n"))
        assertFalse(prompt.contains("Partial"))
        assertFalse(prompt.contains("checkpoint"))
        assertFalse(prompt.contains("Later"))
        assertFalse(prompt.contains("Final"))
        coVerify(exactly = 0) { fixture.dao.getMessage("answer") }
    }

    @Test
    fun wrongConversationOrRunOrMessageIdentityCannotSupplyTheOverlay() = runTest {
        val user = message("user", text = "First input", role = Participant.USER)
        val model = message("answer", parent = user.id, text = "Stored answer")
        val valid = live(model, "Unrelated secret")
        val candidates = listOf(
            valid.copy(conversationId = "other"),
            valid.copy(isGenerating = false),
            valid.copy(streamingMessage = valid.streamingMessage!!.copy(id = "other-message")),
            valid.copy(streamingMessage = valid.streamingMessage!!.copy(parentId = "other-parent")),
            valid.copy(streamingMessage = valid.streamingMessage!!.copy(runId = "other-run")),
            valid.copy(streamingMessage = valid.streamingMessage!!.copy(runSequence = 99L)),
            valid.copy(streamingMessage = valid.streamingMessage!!.copy(participant = Participant.USER)),
        )
        for (snapshot in candidates) {
            val fixture = Fixture(listOf(user, model))
            assertTrue(fixture.generator.generateAndPersist("conversation", snapshot) is
                ConversationTitleGenerator.Result.Success)
            assertTrue(fixture.prompts.single().contains("First input"))
            assertFalse(fixture.prompts.single().contains("Stored answer"))
            coVerify(exactly = 0) { fixture.dao.getMessage("answer") }
            assertFalse(fixture.prompts.single().contains("Unrelated secret"))
        }
    }

    @Test
    fun selectedBranchExcludesOtherPayloadsAndOffBranchLiveText() = runTest {
        val otherUser = message("other-user", text = "Unselected input", role = Participant.USER)
        val user = message("user", text = "Selected input", role = Participant.USER, time = 3L)
        val selected = message("selected", parent = user.id, text = "Selected answer", time = 4L)
        val laterUser = message(
            "later-user", text = "Later input", role = Participant.USER, parent = selected.id, time = 5L,
        )
        val other = message("other", parent = otherUser.id, text = "Unselected answer", time = 2L)
        val fixture = Fixture(listOf(otherUser, other, user, selected, laterUser))
        fixture.header = fixture.header!!.copy(
            selectedBranchesJson = Json.encodeToString(mapOf("null" to user.id, user.id to selected.id)),
        )
        fixture.generator.generateAndPersist("conversation", live(other, "Off branch partial"))

        val prompt = fixture.prompts.single()
        assertTrue(prompt.contains("Selected input"))
        assertFalse(prompt.contains("Selected answer"))
        assertFalse(prompt.contains("Unselected"))
        assertFalse(prompt.contains("Later input"))
        assertFalse(prompt.contains("Off branch"))
        coVerify(exactly = 1) { fixture.dao.getMessage(any()) }
        coVerify(exactly = 1) { fixture.dao.getMessage("user") }
        coVerify(exactly = 1) { fixture.dao.getMessageContextTopology("conversation") }
        coVerify(exactly = 0) { fixture.dao.getMessageContextTopology(neq("conversation")) }
    }

    @Test
    fun blankFirstUserDoesNotFallThroughToLaterUserOrAssistantText() = runTest {
        val fixture = Fixture(listOf(
            message("user", text = "  ", role = Participant.USER),
            message("answer", parent = "user", text = "Assistant text").copy(thoughts = "Private reasoning"),
            message("later-user", parent = "answer", text = "Later input", role = Participant.USER, time = 3L),
        ))
        val result = fixture.generator.generateAndPersist("conversation")

        assertEquals(ConversationTitleGenerator.Result.Failure("Conversation has no usable text"), result)
        assertTrue(fixture.prompts.isEmpty())
        coVerify(exactly = 0) { fixture.settings.awaitInitialLoad() }
        coVerify(exactly = 0) { fixture.dao.updateConversationTitleIfUnchanged(any(), any(), any(), any()) }
    }

    @Test
    fun productionProjectionKeepsAttachmentOnlySource() = runTest {
        val attachment = AttachmentMeta(items = listOf(
            AttachmentItem(type = "file", fileName = "notes.txt", textContent = "Attachment subject"),
        ))
        val fixture = Fixture(listOf(message("user", text = "", role = Participant.USER).copy(
            attachmentMeta = Json.encodeToString(attachment),
        )))
        fixture.generator.generateAndPersist("conversation")

        val prompt = fixture.prompts.single()
        assertTrue(prompt.contains("Attachment subject"))
        assertEquals(1, Regex("Attachment subject").findAll(prompt).count())
    }

    @Test
    fun concurrentManualAndHeadlessTriggersShareOneProviderAndReleaseAdmission() = runTest {
        val fixture = Fixture(listOf(message("user", text = "Subject", role = Participant.USER)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.events = flow {
            entered.complete(Unit)
            release.await()
            emit(StreamEvent.TextChunk("First title"))
        }
        val first = async { fixture.generator.generateAndPersist("conversation") }
        entered.await()
        val headless = ConversationTitleGenerator(fixture.repository, fixture.settings, fixture.registry)
        val joined = CompletableDeferred<Unit>()
        val second = async {
            joined.complete(Unit)
            headless.generateAndPersist("conversation")
        }
        joined.await()
        release.complete(Unit)
        assertEquals(ConversationTitleGenerator.Result.Success("First title"), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, fixture.prompts.size)
        assertEquals(1, fixture.writeAttempts)

        fixture.events = flowOf(StreamEvent.TextChunk("Second title"))
        assertEquals(
            ConversationTitleGenerator.Result.Success("Second title"),
            headless.generateAndPersist("conversation"),
        )
        assertEquals(2, fixture.prompts.size)
    }

    @Test
    fun cancellingAJoinedCallerDoesNotCancelTheAdmittedRequest() = runTest {
        val fixture = Fixture(listOf(message("user", text = "Subject", role = Participant.USER)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.events = flow {
            entered.complete(Unit)
            release.await()
            emit(StreamEvent.TextChunk("Kept title"))
        }
        val admitted = async { fixture.generator.generateAndPersist("conversation") }
        entered.await()
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.generator.generateAndPersist("conversation")
        }
        joined.cancelAndJoin()
        release.complete(Unit)
        assertEquals(ConversationTitleGenerator.Result.Success("Kept title"), admitted.await())
        assertEquals(1, fixture.writeAttempts)
    }

    @Test
    fun renameAndDeletionDuringProviderCallCannotBeOverwritten() = runTest {
        for (delete in listOf(false, true)) {
            val fixture = Fixture(listOf(message("user", text = "Subject", role = Participant.USER)))
            fixture.events = flow {
                fixture.header = if (delete) null else fixture.header!!.copy(title = "Manual rename")
                emit(StreamEvent.TextChunk("Stale generated title"))
            }
            val result = fixture.generator.generateAndPersist("conversation")
            if (delete) {
                assertEquals(ConversationTitleGenerator.Result.Failure("Conversation was deleted"), result)
                assertEquals(null, fixture.header)
            } else {
                assertEquals(ConversationTitleGenerator.Result.Success("Manual rename"), result)
                assertEquals("Manual rename", fixture.header!!.title)
            }
        }
    }

    @Test
    fun cancellationDiscardsPartialOutputAndAllowsTheNextRequest() = runTest {
        val fixture = Fixture(listOf(message("user", text = "Subject", role = Participant.USER)))
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        fixture.events = flow {
            emit(StreamEvent.TextChunk("Incomplete"))
            entered.complete(Unit)
            never.await()
        }
        val pending = async { fixture.generator.generateAndPersist("conversation") }
        entered.await()
        pending.cancelAndJoin()
        assertEquals("Original", fixture.header!!.title)
        assertEquals(0, fixture.writeAttempts)
        fixture.events = flowOf(StreamEvent.TextChunk("Retry title"))
        assertTrue(fixture.generator.generateAndPersist("conversation") is
            ConversationTitleGenerator.Result.Success)
    }

    @Test
    fun streamErrorExceptionAndBlankOutputNeverPersistPartialTitles() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } returns Unit
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val failures = listOf(
                flowOf(
                    StreamEvent.TextChunk("Partial"),
                    StreamEvent.Error(GenerationError.Network(statusCode = 500, message = "Rejected")),
                ),
                flow<StreamEvent> {
                    emit(StreamEvent.TextChunk("Partial"))
                    error("Transport failed")
                },
                flowOf(StreamEvent.TextChunk(" \n\t ")),
            )
            for (events in failures) {
                val fixture = Fixture(listOf(message("user", text = "Subject", role = Participant.USER)))
                fixture.events = events
                assertTrue(fixture.generator.generateAndPersist("conversation") is
                    ConversationTitleGenerator.Result.Failure)
                assertEquals("Original", fixture.header!!.title)
                assertEquals(0, fixture.writeAttempts)
            }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    private class Fixture(initialMessages: List<MessageEntity>) {
        val dao = mockk<ChatDao>(relaxed = true)
        val repository = ConversationRepository(dao, database = null)
        val settings = mockk<SettingsRepository>(relaxed = true)
        val registry = mockk<ProviderRegistry>(relaxed = true)
        val provider = mockk<LlmProvider>()
        var messages = initialMessages
        var header: ChatEntity? = ChatEntity("conversation", "Original", modelId = "Fake:model")
        var events: Flow<StreamEvent> = flowOf(StreamEvent.TextChunk("Generated"))
        val prompts = mutableListOf<String>()
        var writeAttempts = 0
        val generator = ConversationTitleGenerator(repository, settings, registry)

        init {
            coEvery { dao.getConversation("conversation") } answers { header }
            coEvery { dao.getMessageContextTopology("conversation") } answers {
                messages.map { row ->
                    MessageContextTopology(
                        row.id, row.conversationId, row.parentId, row.status, row.participant,
                        row.timestamp, modelName = row.modelName, runId = row.runId,
                        runSequence = row.runSequence, consumedAtPass = row.consumedAtPass,
                    )
                }
            }
            coEvery { dao.getMessage(any()) } answers {
                val id = firstArg<String>()
                messages.singleOrNull { it.id == id }
            }
            coEvery { dao.updateConversationTitleIfUnchanged(any(), any(), any(), any()) } answers {
                writeAttempts++
                val current = header
                if (current != null && current.title == secondArg<String>()) {
                    header = current.copy(title = thirdArg())
                    1
                } else {
                    0
                }
            }
            every { settings.titleGenerationModel } returns MutableStateFlow(null)
            every { settings.titleGenerationPrompt } returns MutableStateFlow("Title prompt")
            every { settings.anthropicCacheEnabled } returns MutableStateFlow(false)
            every { settings.anthropicCacheTtl } returns MutableStateFlow("5m")
            every { settings.customProviders } returns MutableStateFlow(emptyList())
            every { registry.providerForModel(any()) } returns "Fake"
            every { registry.canonicalModelId(any()) } answers { firstArg() }
            every { registry.isConfigured(any(), any()) } returns true
            every { registry.getInstanceOrNull("Fake") } returns provider
            every { registry.getEffectiveBaseUrl(any()) } returns null
            every { provider.generateResponse(any(), any<ProviderConfig>()) } answers {
                prompts += firstArg<List<ChatMessage>>().single().text
                events
            }
        }
    }

    companion object {
        private fun message(
            id: String,
            text: String,
            role: Participant = Participant.MODEL,
            parent: String? = null,
            time: Long = if (role == Participant.USER) 1L else 2L,
        ) = MessageEntity(
            id = id, conversationId = "conversation", parentId = parent, text = text,
            participant = role, status = MessageStatus.SUCCESS, timestamp = time,
            runId = "run", runSequence = if (role == Participant.USER) 0L else 1L,
            consumedAtPass = 0,
        )

        private fun live(entity: MessageEntity, text: String) = ConversationGenerationSnapshot(
            conversationId = entity.conversationId,
            isGenerating = true,
            streamingMessage = ChatMessage(
                id = entity.id, parentId = entity.parentId, text = text,
                participant = entity.participant, status = MessageStatus.SENDING,
                timestamp = entity.timestamp, runId = entity.runId, runSequence = entity.runSequence,
            ),
        )
    }
}
