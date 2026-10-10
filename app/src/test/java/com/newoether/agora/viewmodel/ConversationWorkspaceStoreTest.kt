package com.newoether.agora.viewmodel
import com.newoether.agora.data.local.ChatDao

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWorkspaceStoreTest {
    @Test
    fun absentRowLoadsBlankDraftAndInheritedOverrides() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        val draft = fixture.store.loadDraft(NEW_CHAT_WORKSPACE_ID)
        runCurrent()

        assertEquals(ConversationWorkspaceDraft("", null), draft)
        assertNull(fixture.store.newChatPersist.value)
        assertNull(fixture.store.newChatModelId.value)
        assertNull(fixture.store.newChatSystemPromptId.value)
        assertNull(fixture.store.newChatConversationSettings.value)
        coVerify(exactly = 0) { fixture.conversations.upsertNewChatPersist(any()) }
    }

    @Test
    fun defaultValuedMutationStillCreatesSingletonRow() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        fixture.store.setConversationSettings(NEW_CHAT_WORKSPACE_ID, null)
        fixture.store.awaitNewChatWrites()

        assertEquals(NewChatPersistEntity(), fixture.persisted.value)
        coVerify(exactly = 1) {
            fixture.conversations.upsertNewChatPersist(NewChatPersistEntity())
        }
    }

    @Test
    fun queuedBarrierReturnsOneAuthoritativeWorkspaceSnapshot() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val capturedSettings = ConversationSettings(
            temperature = 0.35f,
            maxTokens = 768,
            lowContextModeEnabled = true,
        )
        runCurrent()

        fixture.store.setModel(NEW_CHAT_WORKSPACE_ID, "provider:model")
        fixture.store.setSystemPrompt(NEW_CHAT_WORKSPACE_ID, "prompt")
        fixture.store.setConversationSettings(NEW_CHAT_WORKSPACE_ID, capturedSettings)

        val snapshot = fixture.store.awaitNewChatWrites()
        assertEquals(true, snapshot.persisted != null)
        assertEquals("provider:model", snapshot.modelId)
        assertEquals("prompt", snapshot.systemPromptId)
        assertEquals(capturedSettings, snapshot.conversationSettings)
    }

    @Test
    fun sendTapBarrierIncludesPriorWritesAndExcludesLaterWorkspaceChanges() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.upsertNewChatPersist(any()) } coAnswers {
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
            fixture.persisted.value = firstArg()
        }
        runCurrent()

        fixture.store.setModel(NEW_CHAT_WORKSPACE_ID, "provider:before-tap")
        runCurrent()
        firstWriteStarted.await()
        val captured = fixture.store.captureNewChatSnapshot()
        fixture.store.setSystemPrompt(NEW_CHAT_WORKSPACE_ID, "after-tap")
        releaseFirstWrite.complete(Unit)

        val snapshot = captured.awaitCaptured()
        assertEquals("provider:before-tap", snapshot.modelId)
        assertNull(snapshot.systemPromptId)
        runCurrent()
        assertEquals("after-tap", fixture.store.newChatSystemPromptId.value)
    }

    @Test
    fun queuedCrossFieldMutationsPreserveCompleteRow() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val capturedSettings = ConversationSettings(
            temperature = 0.4f,
            maxTokens = 512,
            lowContextModeEnabled = true,
        )
        runCurrent()

        fixture.store.setModel(NEW_CHAT_WORKSPACE_ID, "provider:model")
        fixture.store.setSystemPrompt(NEW_CHAT_WORKSPACE_ID, "prompt")
        fixture.store.setConversationSettings(NEW_CHAT_WORKSPACE_ID, capturedSettings)
        fixture.store.updateDraft(NEW_CHAT_WORKSPACE_ID, "draft", "attachments-json")
        runCurrent()

        val persisted = checkNotNull(fixture.persisted.value)
        assertEquals("provider:model", persisted.modelId)
        assertEquals("prompt", persisted.systemPromptId)
        assertEquals("draft", persisted.draftText)
        assertEquals("attachments-json", persisted.draftAttachments)
        assertEquals(
            capturedSettings,
            Json.decodeFromString<ConversationSettings>(
                checkNotNull(persisted.conversationSettingsJson),
            ),
        )
    }

    @Test
    fun ordinaryConversationMetadataMutationsUseTheSharedWorkspace() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var conversation = ChatEntity("conversation", "Title")
        val dao = mockk<ChatDao>()
        every { fixture.conversations.chatDao } returns dao
        coEvery { dao.updateConversationModel("conversation", "provider:model", any()) } answers {
            conversation = conversation.copy(modelId = "provider:model")
            1
        }
        coEvery { fixture.conversations.updateConversationSystemPrompt("conversation", "prompt") } coAnswers {
            conversation = conversation.copy(systemPromptId = "prompt")
            true
        }
        runCurrent()

        fixture.store.setModel("conversation", "provider:model")
        fixture.store.setSystemPrompt("conversation", "prompt")
        runCurrent()

        assertEquals("provider:model", conversation.modelId)
        assertEquals("prompt", conversation.systemPromptId)
        coVerify(exactly = 1) { dao.updateConversationModel("conversation", "provider:model", any()) }
        coVerify(exactly = 1) { fixture.conversations.updateConversationSystemPrompt("conversation", "prompt") }
        coVerify(exactly = 0) { fixture.conversations.upsertConversation(any()) }
    }

    @Test
    fun acceptedClearWaitsBehindEarlierWriteAndLeavesNoRow() = runTest {
        val fixture = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.upsertNewChatPersist(any()) } coAnswers {
            writeStarted.complete(Unit)
            releaseWrite.await()
            fixture.persisted.value = firstArg()
        }
        runCurrent()

        fixture.store.setModel(NEW_CHAT_WORKSPACE_ID, "provider:model")
        writeStarted.await()
        val clear = async {
            fixture.store.clearAcceptedDraft(NEW_CHAT_WORKSPACE_ID)
        }
        runCurrent()

        coVerify(exactly = 0) { fixture.conversations.deleteNewChatPersist(any()) }
        releaseWrite.complete(Unit)
        clear.await()

        assertNull(fixture.persisted.value)
        assertNull(fixture.store.newChatPersist.value)
        coVerify(exactly = 1) { fixture.conversations.deleteNewChatPersist(true) }
    }

    @Test
    fun acceptedClearPreservesWorkspaceFieldsChangedAfterTheSendSnapshot() = runTest {
        val accepted = NewChatPersistEntity(
            modelId = "provider:before",
            draftText = "sent",
        )
        val fixture = Fixture(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initial = accepted,
        )
        runCurrent()

        fixture.store.setModel(NEW_CHAT_WORKSPACE_ID, "provider:after")
        fixture.store.awaitNewChatWrites()
        fixture.store.clearAcceptedDraft(
            ownerId = NEW_CHAT_WORKSPACE_ID,
            reclaimAttachments = false,
            expectedWorkspace = accepted,
        )

        assertEquals(
            accepted.copy(modelId = "provider:after", draftText = ""),
            fixture.persisted.value,
        )
        assertEquals(fixture.persisted.value, fixture.store.newChatPersist.value)
        coVerify(exactly = 0) { fixture.conversations.deleteNewChatPersist(any()) }
    }

    @Test
    fun transactionalRoomDeletionDoesNotResetVisibleWorkspaceBeforeExplicitHandoff() = runTest {
        val initial = NewChatPersistEntity(
            modelId = "provider:model",
            systemPromptId = "prompt",
            conversationSettingsJson = "{\"temperature\":0.2}",
        )
        val fixture = Fixture(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initial = initial,
        )
        runCurrent()
        fixture.persisted.value = null
        runCurrent()
        assertEquals(initial, fixture.store.newChatPersist.value)
        assertEquals("provider:model", fixture.store.newChatModelId.value)
        assertEquals("prompt", fixture.store.newChatSystemPromptId.value)
        fixture.store.clearCommittedNewChatWorkspace()
        assertNull(fixture.store.newChatPersist.value)
    }

    @Test
    fun committedConversationAwaitsOutboxCompletionThenClearsOnlyWhenExplicitlyFinished() = runTest {
        val initial = NewChatPersistEntity(
            modelId = "provider:model",
            conversationSettingsJson = "{}",
        )
        val fixture = Fixture(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initial = initial,
        )
        val transferStarted = CompletableDeferred<Unit>()
        val releaseTransfer = CompletableDeferred<Unit>()
        coEvery { fixture.transfers.complete("conversation") } coAnswers {
            fixture.events += "transfer-start"
            transferStarted.complete(Unit)
            releaseTransfer.await()
            fixture.events += "transfer-complete"
            true
        }
        runCurrent()

        val apply = async {
            fixture.store.applyCommittedNewConversationState("conversation")
        }
        transferStarted.await()
        coVerify(exactly = 0) { fixture.conversations.deleteNewChatPersist(any()) }

        releaseTransfer.complete(Unit)
        apply.await()

        assertEquals(listOf("transfer-start", "transfer-complete"), fixture.events)
        coVerify(exactly = 1) { fixture.transfers.complete("conversation") }
        assertEquals(initial, fixture.persisted.value)
        coVerify(exactly = 0) { fixture.conversations.deleteNewChatPersist(any()) }
        fixture.store.clearCommittedNewChatWorkspace()
        assertEquals(listOf("transfer-start", "transfer-complete", "delete"), fixture.events)
        assertNull(fixture.persisted.value)
        coVerify(exactly = 1) { fixture.conversations.deleteNewChatPersist(false) }
    }

    private class Fixture(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        initial: NewChatPersistEntity? = null,
    ) {
        val persisted = MutableStateFlow(initial)
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>(relaxed = true)
        val transfers = mockk<ConversationSettingsTransferCoordinator>(relaxed = true)
        val events = mutableListOf<String>()
        val store: ConversationWorkspaceStore

        init {
            coEvery { conversations.getNewChatPersist() } answers { persisted.value }
            every { conversations.observeNewChatPersist() } returns persisted
            coEvery { conversations.upsertNewChatPersist(any()) } coAnswers {
                persisted.value = firstArg()
            }
            coEvery { conversations.deleteNewChatPersist(any()) } coAnswers {
                events += "delete"
                val existed = persisted.value != null
                persisted.value = null
                existed
            }
            store = ConversationWorkspaceStore(
                conversations = conversations,
                settings = settings,
                transfers = transfers,
                scope = scope,
                ioDispatcher = dispatcher,
            )
        }
    }
}
