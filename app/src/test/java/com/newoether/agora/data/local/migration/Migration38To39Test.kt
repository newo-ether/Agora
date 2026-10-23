package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adds the subagent columns + parentConversationId index to `conversations`.
 * Upstream authored this as migration 37->38; during the 2026-10-21 upstream
 * sync it was renumbered to 38->39 (after XNet's 36->37 document_embeddings and
 * upstream's todoJson at 37->38). Starts from a real v38 schema (38.json).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration38To39Test {
    @Test fun fullVersion38SchemaMigratesAndValidatesWithoutLosingExistingData() {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/38.json").readText())
            .jsonObject["database"]!!.jsonObject
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "subagent-migration"
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
                        db.execSQL("INSERT INTO conversations (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration) VALUES ('kept','Old title',1,2,1,'user',0,'Keep draft',0)")
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
                    database.openHelper.readableDatabase.query(
                        "SELECT title,draftText,isPinned,parentConversationId,subagentResultJson,subagentResultId,subagentResultStatus,subagentDeliveredResultId FROM conversations WHERE id='kept'",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("Old title", cursor.getString(0))
                        assertEquals("Keep draft", cursor.getString(1))
                        assertEquals(1, cursor.getInt(2))
                        (3..7).forEach { assertTrue(cursor.isNull(it)) }
                    }
                    database.openHelper.readableDatabase.query("PRAGMA index_list(conversations)").use { cursor ->
                        val names = mutableListOf<String>()
                        while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        assertTrue(names.contains("index_conversations_parentConversationId"))
                    }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
