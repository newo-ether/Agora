package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration39To40Test {
    @Test
    fun parentIndexPreservesPayloadAndSupportsIndexedCompactReparenting() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/39.json").readText())
            .jsonObject["database"]!!.jsonObject
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "compact-parent-index-migration"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(39) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema["entities"]!!.jsonArray.forEach { entity ->
                            val table = entity.jsonObject
                            val tableName = table["tableName"]!!.jsonPrimitive.content
                            db.execSQL(table["createSql"]!!.jsonPrimitive.content
                                .replace("\${TABLE_NAME}", tableName))
                            table["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", tableName))
                            }
                        }
                        db.execSQL("""INSERT INTO conversations
                            (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,
                             hasUnreadGeneration,createdAt,selectedBranchesJson)
                            VALUES ('conversation','Keep title',9,8,1,'user',0,'Keep draft',0,1,
                             '{"source":"compact_boundary","compact_boundary":"child"}')
                        """)
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,
                             endedAt,endReason,legacyAmbiguous)
                            VALUES ('run','conversation','COMPLETED',NULL,0,1,2,2,'MODEL_COMPLETED',0)
                        """)
                        for ((id, parent, sequence) in listOf(Triple("source", null, 0),
                            Triple("compact_boundary", "source", 1), Triple("child", "compact_boundary", 2))) {
                            db.execSQL("""INSERT INTO messages
                                (id,conversationId,parentId,text,images,tokenCount,status,participant,
                                 timestamp,runId,runSequence)
                                VALUES (?,'conversation',?,?,'',0,'SUCCESS','MODEL',?,'run',?)
                            """, arrayOf<Any?>(id, parent, "payload-$id", sequence.toLong(), sequence.toLong()))
                        }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            helper.use { it.writableDatabase }
            val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(*ChatDatabase.ALL_MIGRATIONS.toTypedArray())
                .allowMainThreadQueries().build()
            try {
                val db = database.openHelper.writableDatabase
                MIGRATION_39_40.migrate(db) // Reapplying the additive index is harmless.
                db.query("PRAGMA index_info(index_messages_parentId)").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("parentId", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    assertFalse(cursor.moveToNext())
                }
                db.query("EXPLAIN QUERY PLAN UPDATE messages SET parentId = 'source' " +
                    "WHERE parentId = 'compact_boundary'").use { cursor ->
                    val details = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    }
                    assertTrue(details.toString(), details.any { "index_messages_parentId" in it })
                }
                val dao = database.chatDao()
                assertEquals("payload-child", dao.getMessage("child")?.text)
                assertEquals("compact_boundary", dao.getMessage("child")?.parentId)
                assertEquals("Keep draft", dao.getConversation("conversation")?.draftText)
                assertFalse(dao.isOnlyMessageInRun("run", "compact_boundary"))
                assertTrue(dao.removeContextCompact("compact_boundary"))
                assertEquals("source", dao.getMessage("child")?.parentId)
                assertEquals("payload-child", dao.getMessage("child")?.text)
                assertNull(dao.getMessage("compact_boundary"))
                assertNotNull(dao.getRun("run"))
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
