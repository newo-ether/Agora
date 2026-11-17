package com.newoether.agora.data.local.migration

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.AttachmentReferenceIndex
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
class Migration46To47Test {
    @Test fun upgradeInvalidatesCompletedOldIndexWithoutPayloadReadsAndRebuildProtectsFileOnlySharedReference() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/46.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        val metadata = """{"items":[{"type":"file","sandbox_path":"/home/agora/attachments/shared/file.bin","storage":"local_sandbox_runtime"}]}"""
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(ChatDatabase.DB_NAME).callback(object : SupportSQLiteOpenHelper.Callback(46) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { entry ->
                        val table = entry.jsonObject
                        val name = table.getValue("tableName").jsonPrimitive.content
                        db.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        table["indices"]?.jsonArray?.forEach {
                            db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        }
                    }
                    db.execSQL("""INSERT INTO conversations
                        (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt)
                        VALUES ('owner','Keep',1,1,0,'user',0,'',0,1)""")
                    db.execSQL("""INSERT INTO runs
                        (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                        VALUES ('run','owner','COMPLETED',NULL,0,1,1,1,'MODEL_COMPLETED',0)""")
                    db.execSQL("""INSERT INTO messages
                        (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence,attachmentMeta)
                        VALUES ('file_only','owner',?,'',0,'SUCCESS','USER',1,'run',0,?)""",
                        arrayOf("x".repeat(2 * 1024 * 1024), metadata))
                    db.execSQL("INSERT INTO attachment_reference_state VALUES(0,3,NULL,41)")
                    db.execSQL("INSERT INTO attachment_reference_owners VALUES(0,'file_only','old',0,0)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val statements = CopyOnWriteArrayList<String>()
        try {
            helper.use { it.writableDatabase }
            var database = ChatDatabase.build(context, queryCallback = { sql, _ -> statements += sql })
            try {
                assertEquals(47, database.openHelper.writableDatabase.version)
                assertFalse(statements.any { sql -> sql.trimStart().startsWith("SELECT", true) &&
                    ("FROM messages" in sql || "FROM conversations" in sql) })
                database.openHelper.readableDatabase.query("SELECT phase, revision FROM attachment_reference_state").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(42L, it.getLong(1))
                }
                assertTrue(database.conversationAttachmentDirectoryDao().directories("owner").isEmpty())
                val index = AttachmentReferenceIndex(database)
                index.refresh()
                val virtual = AttachmentReferenceIndex.normalizePath("/home/agora/attachments/shared/file.bin")
                assertTrue(index.contains(virtual))
                database.close()
                database = ChatDatabase.build(context)
                assertTrue(AttachmentReferenceIndex(database).contains(virtual))
                database.openHelper.readableDatabase.query("SELECT length(text) FROM messages WHERE id='file_only'").use {
                    assertTrue(it.moveToFirst()); assertEquals(2 * 1024 * 1024, it.getInt(0))
                }
            } finally { database.close() }
        } finally { context.deleteDatabase(ChatDatabase.DB_NAME) }
    }
}
