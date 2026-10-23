package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import java.io.File
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

/**
 * Adds `conversations.todoJson`. Upstream authored this as migration 36->37;
 * during the 2026-10-21 upstream sync it was renumbered to 37->38 so it lands
 * after XNet's existing 36->37 (document_embeddings). Test adapted accordingly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration37To38Test {
    @Test
    fun existingConversationKeepsItsMetadataAndStartsWithoutTodo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "conversation-todo-migration"
        context.deleteDatabase(name)
        val migration = ChatDatabase.ALL_MIGRATIONS.single {
            it.startVersion == 37 && it.endVersion == 38
        }
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, isPinned INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO conversations VALUES ('kept', 'Old title', 1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        migration.migrate(db)
                    }
                }).build(),
        )
        try {
            open(37).use { it.writableDatabase }
            open(38).use { helper ->
                helper.readableDatabase.query("SELECT id,title,isPinned,todoJson FROM conversations").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("Old title", it.getString(1))
                    assertEquals(1, it.getInt(2))
                    assertNull(it.getString(3))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun exportedRoomSchemaContainsNullableTodoAndMigrationIsRegistered() {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) {
            root = root.parentFile ?: error("Unable to locate repository root")
        }
        val schema = Json.parseToJsonElement(File(
            root, "app/schemas/com.newoether.agora.data.local.ChatDatabase/38.json",
        ).readText()).jsonObject["database"]!!.jsonObject
        assertEquals("38", schema["version"]!!.jsonPrimitive.content)
        val conversation = schema["entities"]!!.jsonArray.single {
            it.jsonObject["tableName"]!!.jsonPrimitive.content == "conversations"
        }.jsonObject
        val field = conversation["fields"]!!.jsonArray.single {
            it.jsonObject["fieldPath"]!!.jsonPrimitive.content == "todoJson"
        }.jsonObject
        assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content)
        assertTrue(field["notNull"]?.jsonPrimitive?.content != "true")
        assertTrue(ChatDatabase.CURRENT_VERSION >= 38)
        assertEquals(1, ChatDatabase.ALL_MIGRATIONS.count { it.startVersion == 37 && it.endVersion == 38 })
    }
}
