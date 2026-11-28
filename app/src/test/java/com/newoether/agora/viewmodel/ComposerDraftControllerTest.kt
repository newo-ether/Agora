package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftControllerTest {
    @Test
    fun `load and persist keep one revision checked durable projection`() = runTest {
        val repository = mockk<ConversationRepository>()
        val oldAttachment = attachment("old")
        val newAttachment = attachment("new")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(
            text = "old text",
            attachments = listOf(oldAttachment),
        )
        coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
        coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
        val controller = ComposerDraftController(repository)

        val loaded = controller.load(CONVERSATION_ID)
        val persisted = controller.persist(
            conversationId = CONVERSATION_ID,
            expectedRevision = loaded.revision,
            text = "new text",
            attachments = listOf(newAttachment),
        )

        assertEquals(LoadedComposerDraft("old text", listOf(oldAttachment), 0L), loaded)
        assertEquals(DraftPersistResult(1L, succeeded = true, matchesRequested = true), persisted)
        coVerify(exactly = 1) {
            repository.updateDraft(
                CONVERSATION_ID,
                "new text",
                Json.encodeToString(listOf(newAttachment)),
            )
        }
        coVerify(exactly = 0) {
            repository.deleteUnreferencedDraftAttachmentFiles(listOf(oldAttachment))
        }
    }

    @Test
    fun `cache eviction reloads durable state without writing or reclaiming`() = runTest {
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getConversation(CONVERSATION_ID) } returnsMany listOf(
            chat(text = "initial"),
            chat(text = "external"),
        )
        coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
        coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
        val controller = ComposerDraftController(repository)
        val loaded = controller.load(CONVERSATION_ID)
        val persisted = controller.persist(
            conversationId = CONVERSATION_ID,
            expectedRevision = loaded.revision,
            text = "cached",
            attachments = emptyList(),
        )

        controller.evictCached(CONVERSATION_ID)
        val reloaded = controller.load(CONVERSATION_ID)

        assertEquals(1L, persisted.revision)
        assertEquals(LoadedComposerDraft("external", emptyList(), 0L), reloaded)
        coVerify(exactly = 2) { repository.getConversation(CONVERSATION_ID) }
        coVerify(exactly = 1) { repository.updateDraft(CONVERSATION_ID, "cached", null) }
        coVerify(exactly = 0) { repository.deleteUnreferencedDraftAttachmentFiles(any()) }
    }

    @Test
    fun `private clipboard image ownership survives draft persistence and restore`() = runTest {
        val repository = mockk<ConversationRepository>()
        val privatePath = "/private/clipboard.img"
        val attachment = SelectedAttachment(
            localId = "clipboard",
            uri = "file://$privatePath",
            type = "image",
            mimeType = "image/png",
            localPath = privatePath,
        )
        coEvery { repository.getConversation(CONVERSATION_ID) } returnsMany listOf(
            chat(),
            chat(attachments = listOf(attachment)),
        )
        coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
        val writer = ComposerDraftController(repository)
        val loaded = writer.load(CONVERSATION_ID)

        val persisted = writer.persist(
            conversationId = CONVERSATION_ID,
            expectedRevision = loaded.revision,
            text = "clipboard image",
            attachments = listOf(attachment),
        )
        val restored = ComposerDraftController(repository).load(CONVERSATION_ID)

        assertTrue(persisted.succeeded)
        assertEquals(listOf(attachment), restored.attachments)
        assertEquals("file:///private/clipboard.img", restored.attachments.single().uri)
        assertEquals(privatePath, restored.attachments.single().localPath)
        assertFalse(restored.attachments.single().uri.startsWith("content://"))
        coVerify(exactly = 1) {
            repository.updateDraft(
                CONVERSATION_ID,
                "clipboard image",
                Json.encodeToString(listOf(attachment)),
            )
        }
    }

    @Test
    fun `stale revision cannot overwrite newer snapshot and only reclaims explicit removals`() =
        runTest {
            val repository = mockk<ConversationRepository>()
            val explicitlyRemoved = attachment("removed")
            coEvery { repository.getConversation(CONVERSATION_ID) } returns chat()
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
            coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
            val controller = ComposerDraftController(repository)
            val loaded = controller.load(CONVERSATION_ID)
            val accepted = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = loaded.revision,
                text = "current",
                attachments = emptyList(),
            )

            val stale = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = loaded.revision,
                text = "stale",
                attachments = emptyList(),
                explicitlyRemovedAttachments = listOf(explicitlyRemoved),
            )

            assertEquals(1L, accepted.revision)
            assertEquals(1L, stale.revision)
            assertTrue(stale.succeeded)
            assertFalse(stale.matchesRequested)
            coVerify(exactly = 1) { repository.updateDraft(CONVERSATION_ID, "current", null) }
            coVerify(exactly = 1) {
                repository.deleteUnreferencedDraftAttachmentFiles(listOf(explicitlyRemoved))
            }
        }

    @Test
    fun `accepted clear advances revision after durable write and returns prior attachments`() =
        runTest {
            val repository = mockk<ConversationRepository>()
            val attachment = attachment("accepted")
            coEvery { repository.getConversation(CONVERSATION_ID) } returns
                chat("pending", listOf(attachment))
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
            coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
            val controller = ComposerDraftController(repository)
            controller.load(CONVERSATION_ID)

            val cleared = controller.clearAccepted(CONVERSATION_ID)
            val staleTailFlush = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = 0L,
                text = "pending",
                attachments = listOf(attachment),
            )

            assertEquals(
                DraftClearResult(
                    attachments = listOf(attachment),
                    revision = 1L,
                    succeeded = true,
                ),
                cleared,
            )
            assertEquals(1L, staleTailFlush.revision)
            assertTrue(staleTailFlush.succeeded)
            assertFalse(staleTailFlush.matchesRequested)
            coVerify(exactly = 1) { repository.updateDraft(CONVERSATION_ID, "", null) }
            coVerify(exactly = 0) { repository.deleteUnreferencedDraftAttachmentFiles(any()) }
        }

    @Test
    fun `legacy attachments without import state are upgraded instead of silently ready`() =
        runTest {
            val repository = mockk<ConversationRepository>()
            val raw = """[
                {"localId":"image","uri":"content://legacy/image","type":"image"},
                {"localId":"video","uri":"content://legacy/video","type":"video","localPath":"/private/video"},
                {"localId":"pdf","uri":"content://legacy/pdf","type":"pdf","localPath":"/private/pdf"},
                {"localId":"file","uri":"content://legacy/file","type":"file","localPath":"/private/file"}
            ]""".trimIndent()
            coEvery { repository.getConversation(CONVERSATION_ID) } returns ChatEntity(
                id = CONVERSATION_ID,
                title = "Conversation",
                draftText = "legacy",
                draftAttachments = raw,
            )
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit

            val loaded = ComposerDraftController(repository).load(CONVERSATION_ID)

            assertEquals(1L, loaded.revision)
            assertEquals(
                listOf("image", "video", "pdf", "file"),
                loaded.attachments.map(SelectedAttachment::localId),
            )
            assertTrue(
                loaded.attachments.all {
                    it.importState == AttachmentImportState.PROCESSING
                },
            )
            coVerify(exactly = 1) {
                repository.updateDraft(
                    CONVERSATION_ID,
                    "legacy",
                    Json.encodeToString(loaded.attachments),
                )
            }
        }

    @Test
    fun `accepted clear preserves text persisted after the frozen revision`() = runTest {
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(text = "sent")
        coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
        val controller = ComposerDraftController(repository)
        val frozen = controller.load(CONVERSATION_ID)
        val edited = controller.persist(
            CONVERSATION_ID,
            frozen.revision,
            text = "next draft",
            attachments = emptyList(),
        )

        val cleared = controller.clearAccepted(
            conversationId = CONVERSATION_ID,
            acceptedRevision = frozen.revision,
            acceptedText = "sent",
            acceptedAttachmentIds = emptySet(),
        )

        assertEquals(1L, edited.revision)
        assertEquals(2L, cleared.revision)
        assertEquals("next draft", cleared.remainingText)
        assertTrue(cleared.remainingAttachments.isEmpty())
        coVerify(exactly = 1) {
            repository.updateDraft(CONVERSATION_ID, "next draft", null)
        }
    }

    @Test
    fun `failed durable write preserves revision and does not reclaim attachments`() = runTest {
        val repository = mockk<ConversationRepository>()
        val attachment = attachment("candidate")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat()
        coEvery { repository.updateDraft(any(), any(), any()) } throws IllegalStateException("db")
        val controller = ComposerDraftController(repository)
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs

        try {
            val result = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = 0L,
                text = "not durable",
                attachments = listOf(attachment),
            )

            assertEquals(
                DraftPersistResult(0L, succeeded = false, matchesRequested = false),
                result,
            )
            coVerify(exactly = 0) { repository.deleteUnreferencedDraftAttachmentFiles(any()) }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun `selected legacy draft hydration keeps omitted and explicit READY imports ready`() = runTest {
        val path = "/private/restored.png"
        val legacy = SelectedAttachment(
            localId = "restored", uri = path, localPath = path, type = "image",
            imageArtifacts = listOf(ImageArtifact(path, "image/png", 0L, null, null)),
        )
        val measured = legacy.copy(
            mimeType = "image/png", fileSize = 321L, pixelWidth = 17, pixelHeight = 29,
            imageArtifacts = listOf(ImageArtifact(path, "image/png", 321L, 17, 29)),
        )
        for (encodeDefaults in listOf(false, true)) {
            val repository = mockk<ConversationRepository>()
            val raw = Json { this.encodeDefaults = encodeDefaults }.encodeToString(listOf(legacy))
            coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(text = "legacy").copy(
                draftAttachments = raw,
            )
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
            val visited = mutableListOf<SelectedAttachment>()
            val controller = ComposerDraftController(repository, hydrateAttachment = {
                visited += it
                measured
            })
            assertTrue(visited.isEmpty())

            val loaded = controller.load(CONVERSATION_ID)

            assertEquals(listOf(legacy), visited)
            assertEquals(LoadedComposerDraft("legacy", listOf(measured), 1L), loaded)
            assertTrue(loaded.attachments.single().hasCanonicalReadyArtifact())
            coVerify(exactly = 1) {
                repository.updateDraft(CONVERSATION_ID, "legacy", Json.encodeToString(listOf(measured)))
            }
        }
    }

    @Test
    fun `unavailable restored draft is kept visible without reentering processing`() = runTest {
        val repository = mockk<ConversationRepository>()
        val missing = SelectedAttachment(
            localId = "missing", uri = "", localPath = "", type = "image", unavailable = true,
        )
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(attachments = listOf(missing))

        val loaded = ComposerDraftController(repository).load(CONVERSATION_ID)

        assertEquals(listOf(missing), loaded.attachments)
        assertEquals(0L, loaded.revision)
        assertFalse(loaded.attachments.single().hasCanonicalReadyArtifact())
        coVerify(exactly = 0) { repository.updateDraft(any(), any(), any()) }
    }

    @Test
    fun `selected draft hydration cancellation propagates before any metadata write`() = runTest {
        val repository = mockk<ConversationRepository>()
        val pending = attachment("cancelled")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(attachments = listOf(pending))
        val cancellation = CancellationException("selection changed")
        val controller = ComposerDraftController(repository, hydrateAttachment = { throw cancellation })

        val failure = runCatching { controller.load(CONVERSATION_ID) }.exceptionOrNull()

        assertTrue("Expected cancellation, got $failure", failure is CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancellation })
        coVerify(exactly = 0) { repository.updateDraft(any(), any(), any()) }
    }

    @Test
    fun `selected draft metadata write cancellation does not report a completed load`() = runTest {
        val repository = mockk<ConversationRepository>()
        val pending = attachment("cancelled")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(attachments = listOf(pending))
        val cancellation = CancellationException("selection changed")
        coEvery { repository.updateDraft(any(), any(), any()) } throws cancellation
        val controller = ComposerDraftController(repository, hydrateAttachment = {
            it.copy(unavailable = true)
        })

        val failure = runCatching { controller.load(CONVERSATION_ID) }.exceptionOrNull()

        assertTrue("Expected cancellation, got $failure", failure is CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancellation })
        coVerify(exactly = 1) { repository.updateDraft(any(), any(), any()) }
    }

    private fun chat(
        text: String = "",
        attachments: List<SelectedAttachment> = emptyList(),
    ) = ChatEntity(
        id = CONVERSATION_ID,
        title = "Conversation",
        draftText = text,
        draftAttachments = attachments.takeIf { it.isNotEmpty() }?.let {
            Json.encodeToString(it)
        },
    )

    private fun attachment(id: String) = SelectedAttachment(
        localId = id,
        uri = "content://draft/$id",
        type = "file",
        fileName = "$id.txt",
        localPath = "/private/$id.txt",
        preparedText = "text:$id",
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
