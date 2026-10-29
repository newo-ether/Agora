package com.newoether.agora.data.local.migration

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmbeddingCacheCounts
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.semanticSourceFingerprint
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration41To42Test {
    @Test
    fun migrationPreservesLegacyDataWithoutReadingBodiesAndBackfillRetainsVectors() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/41.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "semantic-meta-upgrade"
        val original = "a".repeat(7999) + "\uD83D\uDE42" + "z".repeat(3 * 1024 * 1024)
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(41) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { entity ->
                            val table = entity.jsonObject
                            val tableName = table.getValue("tableName").jsonPrimitive.content
                            db.execSQL(table.getValue("createSql").jsonPrimitive.content
                                .replace("\${TABLE_NAME}", tableName))
                            table["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", tableName))
                            }
                        }
                        db.execSQL("""INSERT INTO conversations
                            (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,
                             hasUnreadGeneration,createdAt)
                            VALUES ('root','Keep title',9,8,1,'user',0,'Keep draft',0,1)""")
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,
                             endedAt,endReason,legacyAmbiguous)
                            VALUES ('run','root','COMPLETED',NULL,0,1,2,2,'MODEL_COMPLETED',0)""")
                        db.execSQL("""INSERT INTO messages
                            (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence)
                            VALUES ('legacy','root',?,'',0,'SUCCESS','MODEL',7,'run',0)""", arrayOf(original))
                        db.execSQL("""INSERT INTO embeddings
                            (id,messageId,modelId,embedding,chunkText,dimension,sourceFingerprint)
                            VALUES (17,'legacy','model',?,'retained chunk',1,?)""",
                            arrayOf(byteArrayOf(0, 0, 0, 0), semanticSourceFingerprint(original)))
                        db.execSQL("""INSERT INTO semantic_index_ledger
                            (modelId,state,sourceRevision,completedRevision,reconcileRevision,updatedAt)
                            VALUES ('model','CURRENT',1,1,0,1)""")
                        db.execSQL("""INSERT INTO semantic_index_work
                            (modelId,messageId,sourceFingerprint,sourceRevision,updatedAt)
                            VALUES ('model','legacy',?,1,1)""", arrayOf(semanticSourceFingerprint(original)))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val statements = CopyOnWriteArrayList<String>()
        try {
            helper.use { it.writableDatabase }
            val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(*ChatDatabase.ALL_MIGRATIONS.toTypedArray())
                .allowMainThreadQueries().setQueryCallback({ sql, _ -> statements += sql }, Executor { it.run() }).build()
            try {
                assertEquals(ChatDatabase.CURRENT_VERSION, database.openHelper.writableDatabase.version)
                val meta = database.semanticMetadataDao()
                val semantic = database.semanticIndexDao()
                val dao = database.chatDao()
                assertFalse(meta.ensureMetadataState().complete)
                assertNull(meta.getSemanticSource("legacy"))
                assertEquals(SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE, semantic.getLedger("model")?.state)
                assertEquals(2L, semantic.getLedger("model")?.reconcileRevision)
                assertEquals(1L, semantic.getWork("model", "legacy")?.sourceRevision)
                assertFalse(dao.getEmbeddingCacheCounts(listOf("model")).complete)
                assertFalse(statements.any { sql -> sql.trimStart().startsWith("SELECT", true) &&
                    ("FROM messages" in sql || "FROM embeddings" in sql) })

                assertEquals(listOf("legacy"), dao.getEmbeddingSearchPage("model", 0, 1, 16).map { it.messageId })
                assertFalse(meta.ensureMetadataState().complete)
                val ids = meta.getLegacyMessageIds(null, 16)
                assertEquals(listOf("legacy"), ids)
                val source = requireNotNull(meta.getLegacySource("legacy"))
                assertEquals(original.take(8000), source.sourcePrefix.toString(Charsets.UTF_8).take(8000))
                assertEquals(semanticSourceFingerprint(original), source.sourceFingerprint)
                assertTrue(meta.commitLegacyPage(null, listOf(source), ids.last(), true))
                assertTrue(meta.ensureMetadataState().complete)
                assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
                assertEquals(17L, dao.getEmbedding("legacy")?.id)
                assertEquals("retained chunk", dao.getEmbedding("legacy")?.chunkText)
                assertEquals("Keep draft", dao.getConversation("root")?.draftText)
                database.openHelper.readableDatabase.query("SELECT text = ? FROM messages WHERE id = 'legacy'",
                    arrayOf(original)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                assertNotNull(dao.getRun("run"))
                assertFalse(meta.commitLegacyPage(null, listOf(source), ids.last(), true))
                assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
