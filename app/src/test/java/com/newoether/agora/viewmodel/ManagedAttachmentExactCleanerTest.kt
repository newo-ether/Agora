package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationAttachmentDirectory
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real DB/live fences and production dispatch; no-follow unlink is exercised by the native fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ManagedAttachmentExactCleanerTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var database: ChatDatabase
    private val owner = Any()
    private val target get() = File(files.root, "sandbox-home/attachments/exact/input.bin")
    private var unlinks = 0

    @Before fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
        database.chatDao().upsertConversation(ChatEntity("owner", "owner"))
        target.parentFile!!.mkdirs()
        target.writeText("original")
    }

    @After fun close() {
        AttachmentFiles.releaseLivePaths(owner)
        database.close()
        ApplicationProvider.getApplicationContext<Application>().deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun pathReplacementAfterPreparationStillUsesExactNativeBoundaryWithoutJavaUnlinkOrParentPruning() = runBlocking {
        val parked = File(target.parentFile!!.parentFile, "parked")
        val cleaner = cleaner(before = {
            assertTrue(target.parentFile!!.renameTo(parked))
            target.parentFile!!.mkdirs()
            target.writeText("replacement")
        }, unlink = { relative ->
            assertEquals(File("exact", "input.bin").path, relative)
            assertEquals("replacement", target.readText())
            assertEquals("original", File(parked, "input.bin").readText())
            assertTrue(target.delete())
            unlinks++
            true
        })
        assertTrue(cleaner.deleteExact(target.path))
        assertEquals(1, unlinks)
        assertTrue("Exact debt must not prune a replaceable parent in Java", target.parentFile!!.isDirectory)
        assertEquals("original", File(parked, "input.bin").readText())
    }

    @Test fun liveOwnerAdmittedAfterPreparationPreventsExactUnlink() = runBlocking {
        assertFalse(cleaner(before = { AttachmentFiles.setLivePaths(owner, listOf(target.path)) }).deleteExact(target.path))
        assertEquals(0, unlinks)
        assertTrue(target.exists())
    }

    @Test fun ledgerClaimAddedAfterPreparationPreventsExactUnlink() = runBlocking {
        assertTrue(cleaner(before = {
            database.conversationAttachmentDirectoryDao().insert(ConversationAttachmentDirectory("owner", "exact"))
        }).deleteExact(target.path))
        assertEquals(0, unlinks)
        assertTrue(target.exists())
    }

    @Test fun revisionChangedAfterPreparationRemainsRetryableWithoutExactUnlink() = runBlocking {
        assertFalse(cleaner(before = {
            database.chatDao().upsertConversation(ChatEntity("changed", "changed"))
        }).deleteExact(target.path))
        assertEquals(0, unlinks)
        assertTrue(target.exists())
    }

    @Test fun nativeFailureNeverFallsBackToJavaDeleteOrParentCleanup() = runBlocking {
        val failure = runCatching { cleaner(unlink = { throw IOException("no-follow rejected parent") })
            .deleteExact(target.path) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(target.exists())
        assertTrue(target.parentFile!!.isDirectory)
    }

    private fun cleaner(before: suspend () -> Unit = {}, unlink: (String) -> Boolean = { unlinks++; true }) =
        AttachmentOrphanSweeper(database, files.root, exactUnlink = unlink, beforeExactMutation = before)
}
