package com.newoether.agora.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.model.Participant
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeImportWriteBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var db: ChatDatabase
    private val media = NativeConversationMediaRestorer.RestoredMedia(emptyMap(), emptyMap(), emptyMap(), emptyList())

    @Before fun open() = runBlocking {
        context.deleteDatabase("import-boundary")
        db = Room.databaseBuilder(context, ChatDatabase::class.java, "import-boundary").build()
        db.chatDao().upsertConversation(ChatEntity("original", "Keep until commit"))
    }

    @After fun close() {
        db.close()
        context.deleteDatabase("import-boundary")
    }

    @Test fun archivePreparationDoesNotHoldWriterAndPreparedImportRemainsAtomic() = runBlocking {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        var opens = 0
        val entries = entries {
            assertFalse("Archive I/O must precede the write transaction", db.inTransaction())
            if (++opens == 3) {
                preparing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val importing = async(Dispatchers.IO) { import(entries) }
        try {
            assertTrue(preparing.await(10, TimeUnit.SECONDS))
            withTimeout(5_000) { db.chatDao().updateConversationTitle("original", "Foreground committed", 2) }
            assertEquals("Foreground committed", db.chatDao().getConversation("original")?.title)
            assertNull(db.chatDao().getConversation("imported"))
        } finally { release.countDown() }
        importing.await()
        assertNull(db.chatDao().getConversation("original"))
        assertEquals("payload", db.chatDao().getMessage("message")?.text)
        assertNoPreparedFiles()
    }

    @Test fun rejectedFinalCommitRestoresOriginalGraphAndDeletesPreparedSpool() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_import BEFORE INSERT ON messages
            WHEN NEW.id = 'message' BEGIN SELECT RAISE(ABORT, 'test rejection'); END
        """)
        try {
            import(entries { assertFalse(db.inTransaction()) })
            fail("Expected the imported message to be rejected")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("Keep until commit", db.chatDao().getConversation("original")?.title)
        assertNull(db.chatDao().getConversation("imported"))
        assertNull(db.chatDao().getRun("run"))
        assertNoPreparedFiles()
    }

    @Test fun cancelledPreparationNeverCommitsAndDropsTransientObjects() = runBlocking {
        NativeConversationGraphSource.open(entries {}, 4, context.cacheDir).use { graph ->
            var committed = false
            try {
                graph.withPreparedMessages(
                    prepare = { write ->
                        write(MessageEntity("m", "c", text = "x".repeat(100_000),
                            participant = Participant.USER, timestamp = 1, runId = "r"))
                        throw CancellationException("cancelled")
                    },
                    commit = { committed = true },
                )
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertFalse(committed)
        }
        assertNotNull(db.chatDao().getConversation("original"))
        assertNoPreparedFiles()
    }

    private suspend fun import(entries: NativeGraphEntrySource) {
        val importer = NativeConversationGraphImporter(db, db.chatDao(), json,
            NativeConversationMediaRestorer(context, json), scheduleMaintenance = {})
        NativeConversationGraphSource.open(entries, 4, context.cacheDir).use { graph ->
            val headers = graph.open().use {
                importer.readConversationGraphHeaders(it, DataImporter.ImportStrategy.REPLACE, media) { id -> id }
            }
            importer.importConversationGraph(graph, DataImporter.ImportStrategy.REPLACE, headers, media, 4,
                semanticModelSnapshot("", emptyList()))
        }
    }

    private fun entries(onOpen: () -> Unit): NativeGraphEntrySource {
        val payload = NativeConversationPayload(
            conversations = listOf(NativeExportChatEntity("imported", "Imported", 1)),
            runs = listOf(NativeExportRunEntity("run", "imported", startedAt = 1,
                lastCheckpointAt = 1, endedAt = 1, endReason = "MODEL_COMPLETED")),
            messages = listOf(NativeExportMessageEntity("message", "imported", text = "payload",
                participant = "USER", timestamp = 1, runId = "run", runSequence = 0)),
            loops = emptyList(),
        )
        val bytes = json.encodeToString(payload).toByteArray()
        return object : NativeGraphEntrySource {
            override fun has(name: String) = name == NativeBackupFormat.CONVERSATIONS_ENTRY
            override fun bytes(name: String) = bytes.takeIf { has(name) }
            override fun stream(name: String): ByteArrayInputStream? {
                if (!has(name)) return null
                onOpen()
                return ByteArrayInputStream(bytes)
            }
        }
    }

    private fun assertNoPreparedFiles() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("agora-import-messages-") })
    }
}
