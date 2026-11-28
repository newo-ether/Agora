package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueuedAttachmentDirectoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private lateinit var state: ConversationGenerationState
    private val repository get() = ConversationRepository(database.chatDao(), database, scheduleMaintenance = {})
    private val ledger get() = database.conversationAttachmentDirectoryDao()
    private val notifier = SendAcceptanceNotifier(ChatClients())

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
        database.chatDao().upsertConversation(ChatEntity("owner", "owner"))
        state = ConversationGenerationState("owner", reclaimQueuedAttachments = {})
    }
    @After fun close() {
        (state.dispose() + state.queuedSends.value).forEach { AttachmentFiles.releaseLivePaths(it) }
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun registrationIsDurableBeforeQueueAcceptanceAndSurvivesDiscardAndReopen() = runBlocking {
        val queued = queued()
        var notified = false
        state.acceptQueuedSend(repository, "owner", queued, notifier, {
            assertEquals(SendAcceptance.Queued(queued.id, "owner"), it)
            assertEquals(listOf("queued_original"), ledger.directories("owner"))
            assertEquals(listOf(queued), state.queuedSends.value)
            notified = true
        }, null)
        assertTrue(notified)
        state.removeQueuedSend(queued.id)
        AttachmentFiles.releaseLivePaths(queued)
        database.close()
        database = ChatDatabase.build(context)
        assertEquals(listOf("queued_original"), ledger.directories("owner"))
    }

    @Test fun failedRegistrationDoesNotMutateQueueOrNotifyAcceptance() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_queue_directory
            BEFORE INSERT ON conversation_attachment_directories BEGIN SELECT RAISE(ABORT, 'reject queue'); END""")
        var notified = false
        val error = runCatching {
            state.acceptQueuedSend(repository, "owner", queued(), notifier, { notified = true }, null)
        }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(notified)
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(ledger.directories("owner").isEmpty())
    }

    @Test fun deletedConversationCannotAcceptQueuedOriginal() = runBlocking {
        repository.deleteConversation("owner")
        var notified = false
        assertNotNull(runCatching {
            state.acceptQueuedSend(repository, "owner", queued(), notifier, { notified = true }, null)
        }.exceptionOrNull())
        assertFalse(notified)
        assertTrue(state.queuedSends.value.isEmpty())
        assertFalse(ledger.isClaimed("queued_original"))
    }

    @Test fun settlementCancellationRemovesQueueButRetainsOriginalDirectoryClaim() = runBlocking {
        val queued = queued()
        val error = runCatching {
            state.acceptQueuedSend(repository, "owner", queued, notifier, {
                throw CancellationException("cancel draft settlement")
            }, null)
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(ledger.isClaimed("queued_original"))
        AttachmentFiles.releaseLivePaths(queued)
        Unit
    }

    private fun queued() = QueuedSend("queued", "prompt", "provider:model", listOf(SelectedAttachment(
        localId = "queued_original", uri = "file://queued", type = "file",
        localPath = File(context.filesDir, "sandbox-home/attachments/queued_original/input.bin").path,
        sandboxPath = "/home/agora/attachments/queued_original/input.bin",
        storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
    )), "active_run")
}
