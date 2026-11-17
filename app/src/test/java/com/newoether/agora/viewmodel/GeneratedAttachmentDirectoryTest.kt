package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.*
import java.io.File
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
class GeneratedAttachmentDirectoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private val dao get() = database.chatDao()
    private val repository get() = ConversationRepository(dao, database, scheduleMaintenance = {})
    private val ledger get() = database.conversationAttachmentDirectoryDao()
    private val root get() = File(context.filesDir, "sandbox-home/attachments")

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        root.deleteRecursively()
        database = ChatDatabase.build(context)
        dao.upsertConversation(ChatEntity("owner", "owner"))
        dao.insertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.ACTIVE,
            activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
    }
    @After fun close() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        root.deleteRecursively()
    }

    @Test fun generatedRoundClaimsOriginalFromDescriptorAtomicallyAndRetainsItAfterMessageDeletion() = runBlocking {
        val original = original()
        val round = round()
        assertTrue(repository.appendToolRoundToRun(round.entities, 0).inserted)
        assertEquals(listOf("generated"), ledger.directories("owner"))
        assertFalse(repository.appendToolRoundToRun(round.entities, 0).inserted)
        repository.deleteMessagesByIds(round.entities.map { it.id })
        assertTrue(ledger.isClaimed("generated"))
        assertTrue(original.exists())
        database.close()
        database = ChatDatabase.build(context)
        assertTrue(ledger.isClaimed("generated"))
        repository.deleteConversation("owner")
        assertNotNull(database.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_DIRECTORIES, "generated"))
    }

    @Test fun claimFailureRollsBackCompleteToolRoundAndLeavesOriginalUntouched() = runBlocking {
        val original = original()
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_generated_claim
            BEFORE INSERT ON conversation_attachment_directories BEGIN SELECT RAISE(ABORT, 'reject generated'); END""")
        val round = round()
        assertNotNull(runCatching { repository.appendToolRoundToRun(round.entities, 0) }.exceptionOrNull())
        assertTrue(round.entities.all { dao.getMessage(it.id) == null })
        assertFalse(ledger.isClaimed("generated"))
        assertEquals(RunStatus.ACTIVE, dao.getRun("run")!!.status)
        assertTrue(original.exists())
    }

    @Test fun arbitraryToolTextAndUnmanagedDescriptorPathsCannotClaimDirectories() = runBlocking {
        val validText = descriptor("/home/agora/attachments/generated/image.png")
        repository.appendToolRoundToRun(round(toolName = "shell_exec", result = validText).entities, 0)
        repository.appendToolRoundToRun(round(result = descriptor("/home/agora/other/image.png")).entities, 0)
        assertTrue(ledger.directories("owner").isEmpty())
    }

    private fun round(toolName: String = "image_generate", result: String = descriptor("/home/agora/attachments/generated/image.png")) =
        GenerationToolRoundBuilder().build(null, "owner", "run", "model", "provider", listOf(ToolCallData(
            toolName = toolName, arguments = "{}", result = result, toolCallId = "generated",
            resultImages = listOf(ImageArtifact(File(root, "generated/image.png").path, "image/png", 8, 1, 1)),
        )), emptyList())
    private fun descriptor(path: String) = """{"type":"image_generation","status":"ok","images":[{"server":"Local Sandbox","path":"$path","mime_type":"image/png","size_bytes":8,"width":1,"height":1}]}"""
    private fun original() = File(root, "generated/image.png").apply {
        parentFile!!.mkdirs()
        writeText("original")
    }
}
