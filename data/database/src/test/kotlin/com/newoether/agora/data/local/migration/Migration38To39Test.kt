package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration38To39Test {
    @Test fun legacyCreationTimesFreezeEarliestActivityAndKeepGraphsAndReceipts() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/38.json").readText())
            .jsonObject["database"]!!.jsonObject
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "creation-time-migration"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(38) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema["entities"]!!.jsonArray.forEach { entity ->
                            val table = entity.jsonObject
                            val tableName = table["tableName"]!!.jsonPrimitive.content
                            db.execSQL(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                            table["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                            }
                        }
                        for ((id, parent, updated) in listOf(Triple("parent", null, 1000L),
                            Triple("run-child", "parent", 900L), Triple("message-child", "parent", 800L),
                            Triple("empty-child", "parent", 30L))) {
                            db.execSQL("""INSERT INTO conversations
                                (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,
                                hasUnreadGeneration,parentConversationId,subagentResultJson,subagentResultId,
                                subagentResultStatus,subagentDeliveredResultId)
                                VALUES (?, ?, ?, 7, 1, 'user', 0, 'Keep draft', 0, ?, '{}', 'receipt', 'stopped', 'receipt')
                            """, arrayOf<Any?>(id, id, updated, parent))
                        }
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                            VALUES ('run','run-child','COMPLETED',NULL,0,20,21,21,'MODEL_COMPLETED',0)
                        """)
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                            VALUES ('message-run','message-child','COMPLETED',NULL,0,40,41,41,'MODEL_COMPLETED',0)
                        """)
                        db.execSQL("""INSERT INTO messages
                            (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence)
                            VALUES ('kept-message','message-child','Keep message','',0,'SUCCESS','MODEL',10,'message-run',0)
                        """)
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
                val dao = database.chatDao()
                val expected = listOf("message-child", "run-child", "empty-child")
                assertEquals(expected, dao.getSubagentIds("parent"))
                assertEquals(expected, dao.observeSubagents("parent").first().map { it.id })
                for ((id, created) in listOf("parent" to 1000L, "run-child" to 20L,
                    "message-child" to 10L, "empty-child" to 30L)) {
                    val row = checkNotNull(dao.getConversation(id))
                    assertEquals(created, row.createdAt)
                    assertTrue(row.dataChangedAt > 7L)
                    assertEquals("Keep draft", row.draftText)
                    assertTrue(row.isPinned)
                    assertEquals("{}", row.subagentResultJson)
                    assertEquals("receipt", row.subagentResultId)
                    assertEquals("receipt", row.subagentDeliveredResultId)
                }
                assertEquals("Keep message", dao.getMessage("kept-message")?.text)
                assertEquals(20L, dao.getRun("run")?.startedAt)
                dao.updateConversationTitle("message-child", "Renamed", 5000L)
                assertEquals(10L, dao.getConversation("message-child")?.createdAt)
                assertEquals(expected, dao.getSubagentIds("parent"))
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
