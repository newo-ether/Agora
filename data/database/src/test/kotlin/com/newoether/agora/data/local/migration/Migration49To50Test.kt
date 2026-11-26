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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration49To50Test {
    @Test fun measuredGenerationColumnsAreAdditiveAndRoomValidatesSchema() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "measured-generation-migration"
        context.deleteDatabase(name)
        try {
            v47Helper(context, name).use { it.writableDatabase }
            val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(*ChatDatabase.ALL_MIGRATIONS.toTypedArray())
                .allowMainThreadQueries().build()
            try {
                val db = database.openHelper.writableDatabase
                assertEquals(ChatDatabase.CURRENT_VERSION, db.version)
                db.query("SELECT text,generationDurationMs,timeToFirstTokenMs,totalGenerationMs FROM messages")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("measured reply", cursor.getString(0))
                        assertEquals(4000L, cursor.getLong(1))
                        assertTrue(cursor.isNull(2))
                        assertTrue(cursor.isNull(3))
                    }
                db.query("SELECT directory FROM conversation_attachment_directories")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("files/exports", cursor.getString(0))
                    }
                assertEquals("Keep draft", database.chatDao().getConversation("root")?.draftText)
                val message = database.chatDao().getMessage("message")
                assertEquals("measured reply", message?.text)
                assertEquals(4000L, message?.generationDurationMs)
                assertNull(message?.timeToFirstTokenMs)
                assertNull(message?.totalGenerationMs)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    /**
     * An intermediate local build shipped these two columns while still calling its schema v48, so
     * a device that installed it reaches v49 without the usage ledger and with the columns present.
     * Both later steps must tolerate that: the backfill creates the ledger it writes into, and the
     * column additions skip what already exists instead of aborting with "duplicate column name".
     */
    @Test fun anInterimV48WithoutTheLedgerUpgradesWithoutDuplicatingColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "interim-v48-migration"
        context.deleteDatabase(name)
        try {
            val db = v47Helper(context, name).writableDatabase
            db.execSQL("ALTER TABLE messages ADD COLUMN timeToFirstTokenMs INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN totalGenerationMs INTEGER")
            // The backfill only folds in messages that report usage.
            db.execSQL("UPDATE messages SET tokenCount = 12")
            db.version = 48
            MIGRATION_48_49.migrate(db)
            MIGRATION_49_50.migrate(db)
            assertEquals(1, columnCount(db, "messages", "timeToFirstTokenMs"))
            assertEquals(1, columnCount(db, "messages", "totalGenerationMs"))
            db.query("SELECT COUNT(*) FROM usage_ledger").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT text,timeToFirstTokenMs,totalGenerationMs FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("measured reply", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun columnCount(db: SupportSQLiteDatabase, table: String, column: String): Int {
        var count = 0
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == column) count++
        }
        return count
    }

    /** Opens the v47 schema from its exported JSON, with one conversation, run and message. */
    private fun v47Helper(context: Context, name: String): SupportSQLiteOpenHelper {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(
            File(
                root,
                "app/schemas/com.newoether.agora.data.local.ChatDatabase/47.json",
            ).readText(),
        ).jsonObject.getValue("database").jsonObject
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(47) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { entity ->
                            val table = entity.jsonObject
                            val tableName = table.getValue("tableName").jsonPrimitive.content
                            db.execSQL(
                                table.getValue("createSql").jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", tableName),
                            )
                            table["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(
                                    index.jsonObject.getValue("createSql").jsonPrimitive.content
                                        .replace("\${TABLE_NAME}", tableName),
                                )
                            }
                        }
                        db.execSQL(
                            """INSERT INTO conversations
                            (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,
                             hasUnreadGeneration,createdAt)
                            VALUES ('root','Keep title',9,8,1,'user',0,'Keep draft',0,1)""",
                        )
                        db.execSQL(
                            """INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,
                             endedAt,endReason,legacyAmbiguous)
                            VALUES ('run','root','COMPLETED',NULL,0,1,2,2,'MODEL_COMPLETED',0)""",
                        )
                        db.execSQL(
                            """INSERT INTO messages
                            (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,
                             runSequence,generationDurationMs)
                            VALUES ('message','root','measured reply','',0,'SUCCESS','MODEL',7,'run',0,4000)""",
                        )
                        db.execSQL(
                            """INSERT INTO conversation_attachment_directories
                            (conversationId,directory) VALUES ('root','files/exports')""",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
    }
}
