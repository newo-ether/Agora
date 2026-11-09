package com.newoether.agora.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration24To25Test {
    @Test
    fun migrationExecutesTheCompleteTransferOutboxDdl() {
        val database = mockk<SupportSQLiteDatabase>()
        val statement = slot<String>()
        every { database.execSQL(capture(statement)) } just Runs

        MIGRATION_24_25.migrate(database)

        assertEquals(24, MIGRATION_24_25.startVersion)
        assertEquals(25, MIGRATION_24_25.endVersion)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS conversation_settings_transfer ( " +
                "conversationId TEXT NOT NULL, settingsJson TEXT, " +
                "PRIMARY KEY(conversationId), FOREIGN KEY(conversationId) " +
                "REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE )",
            statement.captured.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun generatedRoomSchemaMatchesTheTransferOutboxContract() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(25, database.getValue("version").jsonPrimitive.content.toInt())
        val entity = database.getValue("entities").jsonArray.single {
            it.jsonObject.getValue("tableName").jsonPrimitive.content ==
                "conversation_settings_transfer"
        }.jsonObject
        val fields = entity.getValue("fields").jsonArray.associateBy {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

        assertEquals(setOf("conversationId", "settingsJson"), fields.keys)
        assertTrue(
            fields.getValue("conversationId").jsonObject
                .getValue("notNull").jsonPrimitive.content.toBoolean(),
        )
        assertFalse(fields.getValue("settingsJson").jsonObject.containsKey("notNull"))
        assertEquals(
            listOf("conversationId"),
            entity.getValue("primaryKey").jsonObject
                .getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
        val foreignKey = entity.getValue("foreignKeys").jsonArray.single().jsonObject
        assertEquals("conversations", foreignKey.getValue("table").jsonPrimitive.content)
        assertEquals("CASCADE", foreignKey.getValue("onDelete").jsonPrimitive.content)
        assertEquals("NO ACTION", foreignKey.getValue("onUpdate").jsonPrimitive.content)
        assertEquals(
            listOf("conversationId"),
            foreignKey.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("id"),
            foreignKey.getValue("referencedColumns").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(
                    directory,
                    "app/schemas/com.newoether.agora.data.local.ChatDatabase/25.json",
                ),
                File(
                    directory,
                    "schemas/com.newoether.agora.data.local.ChatDatabase/25.json",
                ),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 25")
    }
}
