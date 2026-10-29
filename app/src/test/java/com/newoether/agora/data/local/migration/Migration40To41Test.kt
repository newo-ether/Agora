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
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration40To41Test {
    @Test fun indexMigrationPreservesExistingDataAndRoomValidatesSchema() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/40.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "keyword-timestamp-index-migration"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(40) {
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
                            VALUES ('message','root','中文 payload','',0,'SUCCESS','MODEL',7,'run',0)""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            helper.use { it.writableDatabase }
            val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(*ChatDatabase.ALL_MIGRATIONS.toTypedArray()).allowMainThreadQueries().build()
            try {
                val db = database.openHelper.writableDatabase
                MIGRATION_40_41.migrate(db)
                db.query("PRAGMA index_info(index_messages_timestamp_id)").use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    assertEquals(listOf("timestamp", "id"), columns)
                }
                assertEquals(ChatDatabase.CURRENT_VERSION, db.version)
                assertEquals("中文 payload", database.chatDao().getMessage("message")?.text)
                assertEquals("Keep draft", database.chatDao().getConversation("root")?.draftText)
                assertNotNull(database.chatDao().getRun("run"))
                db.query("PRAGMA index_info(index_messages_parentId)").use { assertTrue(it.moveToFirst()) }
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
