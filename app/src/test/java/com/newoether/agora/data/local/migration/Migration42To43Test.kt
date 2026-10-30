package com.newoether.agora.data.local.migration

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.AttachmentReferenceIndex
import com.newoether.agora.data.local.ChatDatabase
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
class Migration42To43Test {
    @Test fun upgradeDoesNotScanSourcesAndReferencesPersistAcrossReopen() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/42.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "attachment-upgrade"
        val original = "x".repeat(3 * 1024 * 1024)
        val path = File(context.filesDir, "img_legacy").canonicalPath
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(42) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { entry ->
                            val table = entry.jsonObject
                            val tableName = table.getValue("tableName").jsonPrimitive.content
                            db.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                            table["indices"]?.jsonArray?.forEach {
                                db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                            }
                        }
                        db.execSQL("""INSERT INTO conversations
                            (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt)
                            VALUES ('owner','Retain',1,1,0,'user',0,'draft',0,1)""")
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                            VALUES ('run','owner','COMPLETED',NULL,0,1,1,1,'MODEL_COMPLETED',0)""")
                        db.execSQL("""INSERT INTO messages
                            (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence)
                            VALUES ('legacy','owner',?, ?,0,'SUCCESS','USER',1,'run',0)""",
                            arrayOf(original, "[\"${path.replace("\\", "\\\\")}\"]"))
                        db.execSQL("""INSERT INTO embeddings(messageId, modelId, embedding, chunkText, dimension)
                            VALUES ('legacy','model',?,'source',1)""", arrayOf(byteArrayOf(1, 2, 3, 4)))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val sql = CopyOnWriteArrayList<String>()
        fun open() = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .addMigrations(*ChatDatabase.ALL_MIGRATIONS.toTypedArray()).allowMainThreadQueries()
            .setQueryCallback({ query, _ -> sql += query }, Executor { it.run() }).build()
        try {
            helper.use { it.writableDatabase }
            var database = open()
            try {
                assertEquals(ChatDatabase.CURRENT_VERSION, database.openHelper.writableDatabase.version)
                assertFalse(sql.any { it.trimStart().startsWith("SELECT", true) &&
                    ("FROM messages" in it || "FROM conversations" in it) })
                database.openHelper.readableDatabase.query("SELECT text = ? FROM messages WHERE id = 'legacy'", arrayOf(original)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
                val legacyVector = requireNotNull(database.chatDao().getEmbedding("legacy"))
                assertEquals(0L, legacyVector.generation)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), legacyVector.embedding)
                val references = AttachmentReferenceIndex(database)
                references.refresh()
                assertTrue(references.contains(path))
                assertEquals("draft", database.chatDao().getConversation("owner")?.draftText)
            } finally { database.close() }
            sql.clear()
            database = open()
            try {
                val legacyVector = requireNotNull(database.chatDao().getEmbedding("legacy"))
                assertEquals(0L, legacyVector.generation)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), legacyVector.embedding)
                val references = AttachmentReferenceIndex(database)
                references.refresh()
                assertTrue(references.contains(path))
                assertFalse(sql.any { it.startsWith("SELECT images,") })
                database.openHelper.writableDatabase.execSQL("UPDATE messages SET images = '[]' WHERE id = 'legacy'")
                references.refresh()
                assertFalse(references.contains(path))
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
