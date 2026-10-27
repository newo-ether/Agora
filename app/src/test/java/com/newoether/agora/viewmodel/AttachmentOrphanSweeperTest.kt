package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ConversationDraftAttachmentReference
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageToolMediaReference
import com.newoether.agora.data.local.NewChatDraftAttachmentReference
import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentOrphanSweeperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val database = mockk<ChatDatabase>()
    private val conversations = mockk<ChatDao>()

    @Before
    fun serializeRoomTransactions() {
        val transaction = Mutex()
        every { database.chatDao() } returns conversations
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Any?>(any()) } coAnswers {
            transaction.withLock { arg<suspend () -> Any?>(1).invoke() }
        }
        coEvery { conversations.getMessageAttachmentReferencesPage(any(), any(), any()) } returns emptyList()
        coEvery { conversations.getMessageToolMediaReferencesPage(any(), any(), any()) } returns emptyList()
        coEvery { conversations.getConversationDraftAttachmentReferencesPage(any(), any(), any()) } returns emptyList()
        coEvery { conversations.getNewChatDraftAttachmentReference(any()) } returns null
    }

    @After
    fun releaseRoomMock() = unmockkStatic("androidx.room.RoomDatabaseKt")
    @Test
    fun `discarded queue schedules exact cleanup without deleting another message file`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_queue_sent")
        val attachment = SelectedAttachment(uri = fileUri(image), type = "image", localPath = image.path)
        val debts = mutableListOf<SelectedAttachment>()
        val registry = ConversationStateRegistry { debts += it }
        val state = registry.getOrCreate("conversation")
        state.enqueueSend(QueuedSend("queued", "text", "model", listOf(attachment), "run"))
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root)
        try {
            coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns
                listOf(MessageAttachmentReference("sent", listOf(image.path), null))
            registry.remove("conversation")
            assertTrue("Queue disposal must not directly unlink", image.exists())
            org.junit.Assert.assertEquals(listOf(attachment), debts)
            sweeper.deleteExact(debts.single().localPath!!)
            assertTrue(image.exists())
            coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns emptyList()
            sweeper.deleteExact(image.path)
            assertFalse(image.exists())
        } finally {
            registry.cancelAll()
        }
    }
    @Test
    fun `live image survives sweep exact debt and continuous Room handoff`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_live")
        val owner = Any()
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root, now = { NOW })
        try {
            AttachmentFiles.retainLivePath(owner, image.path)
            sweeper.sweep()
            assertFalse("Live-only ownership defers exact debt completion", sweeper.deleteExact(image.path))
            assertTrue(image.exists())
            coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns
                listOf(MessageAttachmentReference("sent", listOf(image.path), null))
            AttachmentFiles.releaseLivePaths(owner)
            assertTrue("Durable ownership completes obsolete debt", sweeper.deleteExact(image.path))
            assertTrue("Room owns the file after live ownership releases", image.exists())
            coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns emptyList()
            sweeper.deleteExact(image.path)
            assertFalse(image.exists())
        } finally {
            AttachmentFiles.releaseLivePaths(owner)
        }
    }

    @Test
    fun `message ownership arriving between reference scans must not delete the image`() = runTest {
        listOf(false, true).forEach { isNewChat ->
            val image = oldFile(temporaryFolder.root, "img_handoff_$isNewChat")
            val draft = Json.encodeToString(listOf(SelectedAttachment(
                uri = fileUri(image), type = "image", localPath = image.path,
            )))
            var messageReferences = emptyList<MessageAttachmentReference>()
            var draftPresent = true
            val messageScan = CompletableDeferred<Unit>()
            val senderAttempt = CompletableDeferred<Unit>()
            coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, any()) } coAnswers {
                val snapshot = messageReferences
                messageScan.complete(Unit)
                senderAttempt.await()
                yield()
                snapshot
            }
            coEvery { conversations.getConversationDraftAttachmentReferencesPage(null, 64, any()) } answers {
                if (draftPresent && !isNewChat) listOf(ConversationDraftAttachmentReference("draft", draft))
                else emptyList()
            }
            coEvery { conversations.getNewChatDraftAttachmentReference(any()) } answers {
                if (draftPresent && isNewChat) NewChatDraftAttachmentReference(draft) else null
            }
            val sender = launch {
                messageScan.await()
                senderAttempt.complete(Unit)
                database.withTransaction {
                    messageReferences = listOf(MessageAttachmentReference("sent", listOf(image.path), null))
                    draftPresent = false
                }
            }
            AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path)
            sender.join()
            assertTrue(messageReferences.single().images.contains(image.path))
            assertTrue("A persisted message still owns this image", image.exists())
            // Reloaded messages must protect the same file after draft ownership has gone.
            AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path)
            assertTrue(image.exists())
        }
    }

    @Test
    fun `reconcile rechecks an orphan that becomes message owned after the coarse scan`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_late_owner")
        coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns
            listOf(MessageAttachmentReference("sent", listOf(image.path), null))
        AttachmentOrphanSweeper(database, temporaryFolder.root, now = { NOW }).sweep()
        assertTrue(image.exists())
        coVerify { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) }
    }

    @Test
    fun `equal basenames preserve only the referenced canonical file`() = runTest {
        val referenced = oldFile(File(temporaryFolder.root, "images"), "img_same.jpg")
        val orphan = oldFile(temporaryFolder.root, referenced.name)
        coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, referenced.name) } returns
            listOf(MessageAttachmentReference("sent", listOf(fileUri(referenced)), null))
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root)
        sweeper.deleteExact(orphan.path)
        sweeper.deleteExact(referenced.path)
        assertFalse(orphan.exists())
        assertTrue(referenced.exists())
    }

    @Test
    fun `malformed candidate metadata fails closed without deleting the image`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_unreadable")
        coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } returns
            listOf(MessageAttachmentReference("broken", emptyList(), "not-json:${image.name}"))
        val result = runCatching {
            AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path)
        }
        assertTrue(result.exceptionOrNull() is kotlinx.serialization.SerializationException)
        assertTrue(image.exists())
    }

    @Test
    fun `cancelled reference scan and outside root paths cannot unlink files`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_cancelled")
        coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, image.name) } throws
            kotlinx.coroutines.CancellationException("cancelled scan")
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root)
        val cancelled = runCatching { sweeper.deleteExact(image.path) }
        assertTrue(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(image.exists())
        val outside = runCatching {
            AttachmentOrphanSweeper(database, File(temporaryFolder.root, "private")).deleteExact(image.path)
        }
        assertTrue(outside.exceptionOrNull() is IllegalArgumentException)
        assertTrue(image.exists())
    }

    @Test
    fun `sweep preserves every durable and fresh reference while deleting only eligible orphans`() =
        runTest {
            val root = temporaryFolder.root
            val messageImage = oldFile(root, "att_message")
            val messageMetadataImage = oldFile(File(root, "images"), "camera_message")
            val draftLocal = oldFile(File(root, "run-inputs"), "draft_local")
            val draftFrame = oldFile(File(root, "fork-attachments"), "draft_frame")
            val draftRendered = oldFile(root, "pdf_draft")
            val newChatLocal = oldFile(File(root, "run-inputs"), "new_chat_local")
            val newChatFrame = oldFile(File(root, "fork-attachments"), "new_chat_frame")
            val newChatRendered = oldFile(root, "pdf_new_chat")

            val orphanRoot = oldFile(root, "img_orphan")
            val orphanCamera = oldFile(File(root, "images"), "camera_orphan")
            val orphanRunInput = oldFile(File(root, "run-inputs"), "run_orphan")
            val orphanFork = oldFile(File(root, "fork-attachments"), "fork_orphan")
            val freshEligible = file(root, "vid_fresh", NOW - 30 * 60 * 1000L)
            val unrelatedRoot = oldFile(root, "notes.txt")
            val unrelatedImage = oldFile(File(root, "images"), "thumbnail_other")

            coEvery {
                conversations.getMessageAttachmentReferencesPage(null, 64)
            } returns listOf(
                MessageAttachmentReference(
                    id = "message",
                    images = listOf(fileUri(messageImage)),
                    attachmentMeta = Json.encodeToString(
                        AttachmentMeta(
                            listOf(
                                AttachmentItem(
                                    originalUri = fileUri(messageMetadataImage),
                                    type = "image",
                                )
                            )
                        )
                    ),
                ),
                MessageAttachmentReference(
                    id = "malformed-message",
                    images = emptyList(),
                    attachmentMeta = "not-json",
                ),
            )
            coEvery {
                conversations.getConversationDraftAttachmentReferencesPage(null, 64)
            } returns listOf(
                ConversationDraftAttachmentReference(
                    id = "conversation",
                    draftAttachments = Json.encodeToString(
                        listOf(
                            SelectedAttachment(
                                uri = "content://draft",
                                type = "file",
                                localPath = draftLocal.absolutePath,
                                processedFrames = listOf(draftFrame.absolutePath),
                                preRenderedPaths = listOf(draftRendered.absolutePath),
                            )
                        )
                    ),
                ),
                ConversationDraftAttachmentReference(
                    id = "malformed-draft",
                    draftAttachments = "not-json",
                ),
            )
            coEvery {
                conversations.getNewChatDraftAttachmentReference()
            } returns NewChatDraftAttachmentReference(
                draftAttachments = Json.encodeToString(
                    listOf(
                        SelectedAttachment(
                            uri = "content://new-chat",
                            type = "file",
                            localPath = newChatLocal.absolutePath,
                            processedFrames = listOf(newChatFrame.absolutePath),
                            preRenderedPaths = listOf(newChatRendered.absolutePath),
                        )
                    )
                ),
            )

            AttachmentOrphanSweeper(
                database = database,
                filesDirectory = root,
                now = { NOW },
            ).sweep()

            listOf(
                messageImage,
                messageMetadataImage,
                draftLocal,
                draftFrame,
                draftRendered,
                newChatLocal,
                newChatFrame,
                newChatRendered,
                freshEligible,
                unrelatedRoot,
                unrelatedImage,
            ).forEach { retained -> assertTrue(retained.absolutePath, retained.exists()) }
            listOf(orphanRoot, orphanCamera, orphanRunInput, orphanFork).forEach { deleted ->
                assertFalse(deleted.absolutePath, deleted.exists())
            }
            coVerify(exactly = 1) {
                conversations.getMessageAttachmentReferencesPage(null, 64)
            }
            coVerify(exactly = 1) {
                conversations.getConversationDraftAttachmentReferencesPage(null, 64)
            }
            coVerify(exactly = 1) {
                conversations.getNewChatDraftAttachmentReference()
            }
        }

    @Test
    fun `tool only fork file survives exact cleanup until its durable reference is removed`() = runTest {
        val image = oldFile(File(temporaryFolder.root, "fork-attachments"), "tool_only.png")
        val raw = Json.encodeToString(listOf(MessageSegment(type = "tool", toolImages = listOf(artifact(image.path)))))
        coEvery { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) } returns
            listOf(MessageToolMediaReference("tool-row", raw))
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root, now = { NOW })
        sweeper.sweep()
        assertTrue(image.exists())
        coVerify { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) }
        coEvery { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) } returns emptyList()
        sweeper.deleteExact(image.path)
        assertFalse(image.exists())
    }

    @Test
    fun `artifact only metadata and both draft owners protect their private files`() = runTest {
        val message = oldFile(temporaryFolder.root, "img_meta_only")
        val draft = oldFile(temporaryFolder.root, "img_draft_artifact")
        val newChat = oldFile(temporaryFolder.root, "img_new_chat_artifact")
        coEvery { conversations.getMessageAttachmentReferencesPage(null, 64, message.name) } returns listOf(
            MessageAttachmentReference("message", emptyList(), Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageArtifacts = listOf(artifact(message.path))),
            )))),
        )
        fun draftJson(file: File) = Json.encodeToString(listOf(SelectedAttachment(
            uri = "", type = "image", imageArtifacts = listOf(artifact(file.path)),
        )))
        coEvery { conversations.getConversationDraftAttachmentReferencesPage(null, 64, draft.name) } returns
            listOf(ConversationDraftAttachmentReference("draft", draftJson(draft)))
        coEvery { conversations.getNewChatDraftAttachmentReference(newChat.name) } returns
            NewChatDraftAttachmentReference(draftJson(newChat))
        val sweeper = AttachmentOrphanSweeper(database, temporaryFolder.root)
        listOf(message, draft, newChat).forEach { sweeper.deleteExact(it.path); assertTrue(it.exists()) }
    }

    @Test
    fun `unavailable artifact descriptors do not retain stale files`() = runTest {
        val image = oldFile(File(temporaryFolder.root, "fork-attachments"), "unavailable.png")
        val unavailable = artifact(image.path).copy(unavailable = true)
        val raw = Json.encodeToString(listOf(MessageSegment(type = "tool", toolImages = listOf(
            unavailable, unavailable.copy(path = ""),
        ))))
        coEvery { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) } returns
            listOf(MessageToolMediaReference("tool-row", raw))
        AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path)
        assertFalse(image.exists())
    }

    @Test
    fun `malformed candidate tool payload fails closed`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_bad_tool")
        coEvery { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) } returns
            listOf(MessageToolMediaReference("broken", "not-json:${image.name}"))
        val result = runCatching { AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path) }
        assertTrue(result.exceptionOrNull() is kotlinx.serialization.SerializationException)
        assertTrue(image.exists())
    }

    @Test
    fun `tool reference lookup pages by id and narrows every page to the candidate token`() = runTest {
        val image = oldFile(temporaryFolder.root, "img_paged_tool")
        coEvery { conversations.getMessageToolMediaReferencesPage(null, 64, image.name) } returns
            (0 until 64).map { MessageToolMediaReference("row-$it", "[]") }
        val raw = Json.encodeToString(listOf(MessageSegment(type = "tool", toolImages = listOf(artifact(image.path)))))
        coEvery { conversations.getMessageToolMediaReferencesPage("row-63", 64, image.name) } returns
            listOf(MessageToolMediaReference("row-last", raw))
        AttachmentOrphanSweeper(database, temporaryFolder.root).deleteExact(image.path)
        assertTrue(image.exists())
        coVerify(exactly = 1) { conversations.getMessageToolMediaReferencesPage("row-63", 64, image.name) }
        coVerify(exactly = 0) { conversations.getMessageToolMediaReferencesPage(any(), any(), null) }
    }

    private fun artifact(path: String) = ImageArtifact(
        path = path, mimeType = "image/png", sizeBytes = 4, width = 40, height = 30,
    )

    private fun oldFile(parent: File, name: String): File =
        file(parent, name, NOW - 2 * 60 * 60 * 1000L)

    private fun file(parent: File, name: String, lastModified: Long): File {
        parent.mkdirs()
        return File(parent, name).also { file ->
            file.writeText("test")
            check(file.setLastModified(lastModified))
        }
    }

    private fun fileUri(file: File): String = "file://${file.absolutePath}"

    private companion object {
        const val NOW = 10_000_000L
    }
}
