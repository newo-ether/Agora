package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.model.*
import com.newoether.agora.util.AttachmentFiles
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

/** Exercises the production DB/live fences; filesystem no-follow behavior has a native fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ManagedAttachmentDirectoryCleanerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private val owner = Any()
    private val target get() = File(context.filesDir, "sandbox-home/attachments/shared")
    private var steps = 0

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
        database.chatDao().upsertConversation(ChatEntity("owner", "owner"))
        database.chatDao().insertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.COMPLETED,
            activeSlot = null, startedAt = 1, lastCheckpointAt = 1, endedAt = 1, endReason = RunEndReason.MODEL_COMPLETED))
    }
    @After fun close() {
        AttachmentFiles.releaseLivePaths(owner)
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun sharedLedgerClaimPreventsAnyFilesystemMutation() = runBlocking {
        database.conversationAttachmentDirectoryDao().insert(ConversationAttachmentDirectory("owner", "shared"))
        assertTrue(cleaner().delete("shared"))
        assertEquals(0, steps)
    }

    @Test fun legacyFileOnlySandboxReferencePreventsMutationWithoutLedgerClaim() = runBlocking {
        val metadata = Json.encodeToString(AttachmentMeta(listOf(AttachmentItem(type = "file",
            sandboxPath = "/home/agora/attachments/shared/input.bin", storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME))))
        insert(attachmentMeta = metadata)
        assertTrue(cleaner().delete("shared"))
        assertEquals(0, steps)
    }

    @Test fun legacyGeneratedDescriptorProtectsOriginalEvenWhenToolImagesPointOnlyToPrivateSnapshot() = runBlocking {
        val descriptor = """{"type":"image_generation","status":"ok","images":[{"server":"Local Sandbox","path":"/home/agora/attachments/shared/image.png"}]}"""
        insert(toolCallJson = Json.encodeToString(listOf(MessageSegment(type = "tool", toolName = "image_generate",
            toolResult = descriptor, toolImages = listOf(ImageArtifact(File(context.filesDir, "img_snapshot").path,
                "image/png", 1, 1, 1))))))
        assertFalse(database.conversationAttachmentDirectoryDao().isClaimed("shared"))
        assertTrue(cleaner().delete("shared"))
        assertEquals(0, steps)
    }

    @Test fun arbitraryToolTextDoesNotEstablishGeneratedOriginalOwnership() = runBlocking {
        insert(toolCallJson = Json.encodeToString(listOf(MessageSegment(type = "tool", toolName = "shell_exec",
            toolResult = """{"type":"image_generation","status":"ok","images":[{"server":"Local Sandbox","path":"/home/agora/attachments/shared/image.png"}]}"""))))
        assertTrue(cleaner().delete("shared"))
        assertEquals(1, steps)
    }

    @Test fun liveDescendantPreventsDetachmentUntilOwnerReleasesIt() = runBlocking {
        AttachmentFiles.setLivePaths(owner, listOf(File(target, "input.bin").path))
        assertFalse(cleaner().delete("shared"))
        assertEquals(0, steps)
        AttachmentFiles.releaseLivePaths(owner)
        assertTrue(cleaner().delete("shared"))
        assertEquals(1, steps)
    }

    @Test fun referenceRevisionIsRecheckedAfterPreparationBeforeMutation() = runBlocking {
        val cleaner = cleaner(before = {
            database.chatDao().upsertConversation(ChatEntity("changed", "changed"))
        })
        assertFalse(cleaner.delete("shared"))
        assertEquals(0, steps)
    }

    @Test fun claimAddedAtFinalBoundaryPreventsMutationEvenWithoutReferenceRevisionChange() = runBlocking {
        val cleaner = cleaner(before = {
            database.conversationAttachmentDirectoryDao().insert(ConversationAttachmentDirectory("owner", "shared"))
        })
        assertTrue(cleaner.delete("shared"))
        assertEquals(0, steps)
    }

    @Test fun everyStepRechecksOwnersAndBoundedAttemptsRemainRetryable() = runBlocking {
        val blocked = cleaner(step = {
            steps++
            AttachmentFiles.setLivePaths(owner, listOf(File(target, "new-live.bin").path))
            1
        })
        assertFalse(blocked.delete("shared"))
        assertEquals(1, steps)
        AttachmentFiles.releaseLivePaths(owner)
        steps = 0
        assertFalse(cleaner(step = { steps++; 1 }).delete("shared"))
        assertEquals(128, steps)
    }

    @Test fun cancellationBeforeFinalBoundaryDoesNotMutateFilesystem() = runBlocking {
        val error = runCatching { cleaner(before = { throw CancellationException("cancel cleanup") }).delete("shared") }
            .exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, steps)
    }

    private fun cleaner(before: suspend () -> Unit = {}, step: (String) -> Int = { steps++; 0 }) =
        ManagedAttachmentDirectoryCleaner(database, context.filesDir, AttachmentReferenceIndex(database), step, before)
    private suspend fun insert(attachmentMeta: String? = null, toolCallJson: String? = null) {
        database.chatDao().insertMessage(MessageEntity("message", "owner", text = "", timestamp = 1,
            participant = Participant.USER, runId = "run", runSequence = 0,
            attachmentMeta = attachmentMeta, toolCallJson = toolCallJson))
    }
}
