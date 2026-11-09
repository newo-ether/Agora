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
import org.junit.Test

class Migration26To27Test {
    @Test
    fun migrationCreatesSingletonConversationSettingsImportOutbox() {
        val database = mockk<SupportSQLiteDatabase>()
        val statement = slot<String>()
        every { database.execSQL(capture(statement)) } just Runs

        MIGRATION_26_27.migrate(database)

        assertEquals(26, MIGRATION_26_27.startVersion)
        assertEquals(27, MIGRATION_26_27.endVersion)
        assertEquals(
            """
            CREATE TABLE IF NOT EXISTS conversation_settings_import_transfer (
                id INTEGER NOT NULL,
                transferId TEXT NOT NULL,
                settingsJson TEXT NOT NULL,
                mode TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            statement.captured,
        )
    }

    @Test
    fun generatedRoomSchemaContainsConversationSettingsImportOutbox() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(27, database.getValue("version").jsonPrimitive.content.toInt())
        val entity = database.getValue("entities").jsonArray.single {
            it.jsonObject.getValue("tableName").jsonPrimitive.content ==
                "conversation_settings_import_transfer"
        }.jsonObject
        val fields = entity.getValue("fields").jsonArray.associateBy {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }
        assertEquals(setOf("id", "transferId", "settingsJson", "mode"), fields.keys)
        assertEquals("INTEGER", fields.getValue("id").jsonObject.getValue("affinity").jsonPrimitive.content)
        assertEquals("TEXT", fields.getValue("transferId").jsonObject.getValue("affinity").jsonPrimitive.content)
        assertFalse(entity.containsKey("foreignKeys"))
    }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(
                    directory,
                    "app/schemas/com.newoether.agora.data.local.ChatDatabase/27.json",
                ),
                File(
                    directory,
                    "schemas/com.newoether.agora.data.local.ChatDatabase/27.json",
                ),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 27")
    }
}
