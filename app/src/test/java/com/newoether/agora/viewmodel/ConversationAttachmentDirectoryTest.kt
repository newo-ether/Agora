package com.newoether.agora.viewmodel

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercises the real admission/deletion transactions and persistent directory ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationAttachmentDirectoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private val dao get() = database.chatDao()
    private val ledger get() = database.conversationAttachmentDirectoryDao()
    private val repository get() = ConversationRepository(dao, database, scheduleMaintenance = {})
    private val root get() = File(context.filesDir, "sandbox-home/attachments")
    private fun sweeper() = AttachmentOrphanSweeper(database, context.filesDir, directoryStep = { directory ->
        File(root, directory).deleteRecursively()
        0
    })

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        root.deleteRecursively()
        database = ChatDatabase.build(context)
        dao.upsertConversation(ChatEntity("owner", "owner", lastUpdated = 1))
    }
    @After fun close() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        root.deleteRecursively()
    }

    @Test fun acceptedInputClaimsSurviveMessageDeletionAndDatabaseReopenUntilConversationDeletion() = runBlocking {
        val original = original("input", "original.bin")
        val preview = File(context.filesDir, "img_directory_preview").apply { writeText("snapshot") }
        val request = request("owner", "run", "input", preview.path)
        val committed = AcceptedInputGraphWriter(repository).commit(request)
        assertEquals(listOf("input"), ledger.directories("owner"))
        repository.deleteMessagesByIds(listOf(committed.userMessage.id))
        assertTrue(original.exists())
        assertTrue(sweeper().deleteExact(preview.path))
        assertFalse(preview.exists())
        database.close()
        database = ChatDatabase.build(context)
        assertEquals(listOf("input"), ledger.directories("owner"))
        repository.deleteConversation("owner")
        assertFalse(ledger.isClaimed("input"))
        assertNotNull(database.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_DIRECTORIES, "input"))
        assertTrue(original.exists())
        assertTrue(sweeper().deleteDirectory("input"))
        assertFalse(original.parentFile!!.exists())
    }

    @Test fun firstNewChatCommitConsumesWorkspaceAndClaimsOriginalInOneTransaction() = runBlocking {
        val original = original("new_input", "notes.txt")
        val pending = selected("new_input", original)
        val workspace = NewChatPersistEntity(draftText = "prompt", draftAttachments = Json.encodeToString(listOf(pending)))
        dao.upsertNewChatPersist(workspace)
        val committed = AcceptedInputGraphWriter(repository).commit(
            request("new", "new_run", "new_input").copy(
                newConversation = ChatEntity("new", "new"), newChatPersistSnapshot = workspace,
            ),
        )
        assertEquals("new", committed.userMessage.conversationId)
        assertNull(dao.getNewChatPersist())
        assertEquals(listOf("new_input"), ledger.directories("new"))
        assertTrue(original.exists())
    }

    @Test fun failedClaimRollsBackNewChatGraphAndWorkspaceConsumption() = runBlocking {
        val pending = selected("blocked", original("blocked", "input.bin"))
        val workspace = NewChatPersistEntity(draftAttachments = Json.encodeToString(listOf(pending)))
        dao.upsertNewChatPersist(workspace)
        rejectClaims()
        val error = runCatching {
            AcceptedInputGraphWriter(repository).commit(request("new", "new_run", "blocked").copy(
                newConversation = ChatEntity("new", "new"), newChatPersistSnapshot = workspace,
            ))
        }.exceptionOrNull()
        assertNotNull(error)
        assertNull(dao.getConversation("new"))
        assertNull(dao.getRun("new_run"))
        assertNull(dao.getMessage("new_run_user"))
        assertEquals(workspace, dao.getNewChatPersist())
        assertFalse(ledger.isClaimed("blocked"))
    }

    @Test fun cancellationInsideAdmissionTransactionRollsBackClaimsAndGraph() = runBlocking {
        val original = original("cancelled", "input.bin")
        val error = runCatching {
            database.withTransaction {
                AcceptedInputGraphWriter(repository).commit(request("owner", "cancelled_run", "cancelled"))
                assertTrue(ledger.isClaimed("cancelled"))
                throw CancellationException("cancel admission")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(dao.getRun("cancelled_run"))
        assertFalse(ledger.isClaimed("cancelled"))
        assertTrue(original.exists())
    }

    @Test fun legacyMessageDeletionClaimsOnlyProvablePathsAndKeepsClaimAfterPayloadIsGone() = runBlocking {
        val owned = original("legacy", "old.bin")
        val unknown = original("unprovable", "old.bin")
        terminalRun("legacy_run", "owner")
        dao.insertMessage(message("old", "owner", "legacy_run", "legacy"))
        assertFalse(ledger.isClaimed("legacy"))
        repository.deleteMessagesByIds(listOf("old"))
        assertTrue(ledger.isClaimed("legacy"))
        repository.deleteConversation("owner")
        assertTrue(sweeper().deleteDirectory("legacy"))
        assertFalse(owned.exists())
        assertTrue(unknown.exists())
        assertNull(database.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_DIRECTORIES, "unprovable"))
    }

    @Test fun forkClaimsSelectedOriginalsOnlyAndLastOwnerDeletionReclaimsSharedDirectory() = runBlocking {
        val shared = original("shared", "source.bin")
        original("deleted_history", "older.bin")
        terminalRun("source_run", "owner")
        repository.upsertMessage(message("selected", "owner", "source_run", "shared"))
        repository.upsertMessage(message("older", "owner", "source_run", "deleted_history").copy(runSequence = 1))
        repository.deleteMessagesByIds(listOf("older"))
        val forkRun = terminalRunEntity("fork_run", "fork")
        repository.createForkGraph(
            ChatEntity("fork", "fork"), listOf(forkRun),
            listOf(message("fork_selected", "fork", "fork_run", "shared")),
            mapOf("selected" to "fork_selected"),
        )
        assertEquals(listOf("shared"), ledger.directories("fork"))
        repository.deleteConversation("owner")
        assertTrue(sweeper().deleteDirectory("shared"))
        assertTrue(shared.exists())
        repository.deleteMessagesByIds(listOf("fork_selected"))
        assertTrue(sweeper().deleteDirectory("shared"))
        assertTrue(shared.exists())
        repository.deleteConversation("fork")
        assertTrue(sweeper().deleteDirectory("shared"))
        assertFalse(shared.exists())
    }

    @Test fun failedForkClaimLeavesNoForkConversationRunOrMessage() = runBlocking {
        terminalRun("source_run", "owner")
        dao.insertMessage(message("selected", "owner", "source_run", "shared"))
        rejectClaims()
        assertNotNull(runCatching {
            repository.createForkGraph(ChatEntity("fork", "fork"), listOf(terminalRunEntity("fork_run", "fork")),
                listOf(message("fork_selected", "fork", "fork_run", "shared")), mapOf("selected" to "fork_selected"))
        }.exceptionOrNull())
        assertNull(dao.getConversation("fork"))
        assertNull(dao.getRun("fork_run"))
        assertNull(dao.getMessage("fork_selected"))
        assertFalse(ledger.isClaimed("shared"))
    }

    @Test fun conversationDeletionFailureRollsBackDirectoryDebtAndClaimCascade() = runBlocking {
        terminalRun("source_run", "owner")
        repository.upsertMessage(message("selected", "owner", "source_run", "shared"))
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_conversation_delete
            BEFORE DELETE ON conversations BEGIN SELECT RAISE(ABORT, 'reject deletion'); END""")
        assertNotNull(runCatching { repository.deleteConversation("owner") }.exceptionOrNull())
        assertNotNull(dao.getConversation("owner"))
        assertTrue(ledger.isClaimed("shared"))
        assertNull(database.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_DIRECTORIES, "shared"))
    }

    private fun request(owner: String, run: String, directory: String, preview: String? = null) = AcceptedInputGraphWriter.Request(
        inputEffect = RunEffect.PersistAcceptedInput(RunEffectIdentity(owner, 1L, run, 0, "send-$run")),
        userMessageId = "${run}_user", modelMessageId = "${run}_model", userText = "prompt",
        images = listOfNotNull(preview), attachmentMeta = metadata(directory), modelId = "provider:model",
        userTimestamp = 100, touchConversationOnAdmission = true,
    )
    private fun metadata(directory: String) = Json.encodeToString(AttachmentMeta(listOf(AttachmentItem(
        type = "file", sandboxPath = "/home/agora/attachments/$directory/original.bin",
        storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
    ))))
    private fun message(id: String, owner: String, run: String, directory: String) = MessageEntity(
        id = id, conversationId = owner, text = "", timestamp = 1, participant = Participant.USER,
        runId = run, runSequence = 0, attachmentMeta = metadata(directory),
    )
    private suspend fun terminalRun(id: String, owner: String) = dao.insertRun(terminalRunEntity(id, owner))
    private fun terminalRunEntity(id: String, owner: String) = RunEntity(id, owner, parentRunId = null,
        status = RunStatus.COMPLETED, activeSlot = null, startedAt = 1, lastCheckpointAt = 1,
        endedAt = 1, endReason = RunEndReason.MODEL_COMPLETED)
    private fun original(directory: String, name: String) = File(root, "$directory/$name").apply {
        parentFile!!.mkdirs(); writeText("original")
    }
    private fun selected(directory: String, original: File) = SelectedAttachment(
        localId = directory, uri = "file://${original.path}", type = "file", localPath = original.path,
        storage = AttachmentStorage.LOCAL_SANDBOX_PENDING, sandboxPath = "/home/agora/attachments/$directory/${original.name}",
    )
    private fun rejectClaims() = database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_directory_claim
        BEFORE INSERT ON conversation_attachment_directories BEGIN SELECT RAISE(ABORT, 'reject claim'); END""")
}
