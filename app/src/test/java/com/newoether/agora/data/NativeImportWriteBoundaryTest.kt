package com.newoether.agora.data

import android.app.Application
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
        context.deleteDatabase(ChatDatabase.DB_NAME)
        db = ChatDatabase.build(context)
        db.chatDao().upsertConversation(ChatEntity("original", "Keep until commit"))
    }

    @After fun close() {
        db.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
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
            WHEN NEW.id = 'message-129' BEGIN SELECT RAISE(ABORT, 'test rejection'); END
        """)
        try {
            import(entries(count = 130) { assertFalse(db.inTransaction()) })
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

    @Test fun multiPageMergeKeepsExistingLocalMessageAndItsVector() = runBlocking {
        import(entries(count = 130) {})
        val dao = db.chatDao()
        dao.upsertMessage(requireNotNull(dao.getMessage("message")).copy(text = "local edit"))
        dao.upsertEmbedding(com.newoether.agora.data.local.EmbeddingEntity(
            messageId = "message", modelId = "model", embedding = ByteArray(4),
            dimension = 1, chunkText = "local edit",
        ))
        val before = dao.getEmbedding("message")
        import(entries(count = 131) {}, DataImporter.ImportStrategy.MERGE)
        assertEquals("local edit", dao.getMessage("message")?.text)
        assertEquals(before, dao.getEmbedding("message"))
        assertEquals("payload", dao.getMessage("message-130")?.text)
        assertNoPreparedFiles()
    }

    @Test fun preparationIoFailureKeepsOriginalGraphAndCleansItsPartialSpool() = runBlocking {
        var opens = 0
        try {
            import(entries(count = 130) {
                if (++opens == 3) throw java.io.IOException("simulated storage failure")
            })
            fail("Expected preparation failure")
        } catch (failure: java.io.IOException) {
            assertEquals("simulated storage failure", failure.message)
        }
        assertEquals("Keep until commit", db.chatDao().getConversation("original")?.title)
        assertNull(db.chatDao().getConversation("imported"))
        assertNoPreparedFiles()
    }

    @Test fun abandonedPreparedFilesAreReclaimedButActiveImportsAndUnrelatedFilesArePreserved() = runBlocking {
        val abandoned = java.io.File(context.cacheDir, "agora-import-messages-abandoned.bin").apply { writeText("partial") }
        val unrelated = java.io.File(context.cacheDir, "keep-import-notes.bin").apply { writeText("keep") }
        val locked = java.io.File(context.cacheDir, "agora-import-messages-active.bin").apply { writeText("active") }
        java.io.RandomAccessFile(locked, "rw").use { owner ->
            owner.channel.lock().use {
                import(entries {})
                assertFalse(abandoned.exists())
                assertTrue(locked.exists())
                owner.seek(0)
                val contents = ByteArray(6).also { owner.readFully(it) }
                assertEquals("active", contents.decodeToString())
                assertEquals("keep", unrelated.readText())
            }
        }
        // The next admission sees the same unlocked file state left after a process exits.
        import(entries {})
        assertFalse(locked.exists())
        assertEquals("keep", unrelated.readText())
        unrelated.delete()
        assertNoPreparedFiles()
    }

    @Test fun nestedPreparationCannotDeleteTheOuterImportsPreparedMessages() = runBlocking {
        NativeConversationGraphSource.open(entries {}, 4, context.cacheDir).use { outer ->
            outer.withPreparedMessages(
                prepare = { write ->
                    write(MessageEntity("outer", "c", text = "outer payload", participant = Participant.USER,
                        timestamp = 1, runId = "r"))
                    NativeConversationGraphSource.open(entries {}, 4, context.cacheDir).use { inner ->
                        inner.withPreparedMessages(
                            prepare = { innerWrite -> innerWrite(MessageEntity("inner", "c", text = "inner payload",
                                participant = Participant.USER, timestamp = 1, runId = "r")) },
                            commit = { assertEquals("inner", it.single().id) },
                        )
                    }
                },
                commit = { assertEquals("outer payload", it.single().text) },
            )
        }
        assertNoPreparedFiles()
    }

    private suspend fun import(entries: NativeGraphEntrySource, strategy: DataImporter.ImportStrategy = DataImporter.ImportStrategy.REPLACE) {
        val importer = NativeConversationGraphImporter(db, db.chatDao(), json,
            NativeConversationMediaRestorer(context, json), scheduleMaintenance = {})
        NativeConversationGraphSource.open(entries, 4, context.cacheDir).use { graph ->
            val headers = graph.open().use {
                importer.readConversationGraphHeaders(it, strategy, media) { id -> id }
            }
            importer.importConversationGraph(graph, strategy, headers, media, 4,
                semanticModelSnapshot("", emptyList()))
        }
    }

    private fun entries(count: Int = 1, onOpen: () -> Unit): NativeGraphEntrySource {
        val payload = NativeConversationPayload(
            conversations = listOf(NativeExportChatEntity("imported", "Imported", 1)),
            runs = listOf(NativeExportRunEntity("run", "imported", startedAt = 1,
                lastCheckpointAt = 1, endedAt = 1, endReason = "MODEL_COMPLETED")),
            messages = (0 until count).map { index ->
                NativeExportMessageEntity(if (index == 0) "message" else "message-$index",
                    "imported", text = "payload", participant = "USER", timestamp = index.toLong() + 1,
                    runId = "run", runSequence = index.toLong())
            },
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
