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

class Migration23To24Test {
    @Test
    fun migrationExecutesTheCompleteSingletonDdl() {
        val database = mockk<SupportSQLiteDatabase>()
        val statement = slot<String>()
        every { database.execSQL(capture(statement)) } just Runs

        MIGRATION_23_24.migrate(database)

        assertEquals(23, MIGRATION_23_24.startVersion)
        assertEquals(24, MIGRATION_23_24.endVersion)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS new_chat_persist ( " +
                "id INTEGER NOT NULL, modelId TEXT, systemPromptId TEXT, " +
                "conversationSettingsJson TEXT, draftText TEXT NOT NULL, " +
                "draftAttachments TEXT, PRIMARY KEY(id) )",
            statement.captured.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun generatedRoomSchemaMatchesTheMigratedSingletonContract() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val entities = root.getValue("database").jsonObject
            .getValue("entities").jsonArray
        val entity = entities.single {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == "new_chat_persist"
        }.jsonObject
        val fields = entity.getValue("fields").jsonArray.associateBy {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

        assertEquals(
            setOf(
                "id",
                "modelId",
                "systemPromptId",
                "conversationSettingsJson",
                "draftText",
                "draftAttachments",
            ),
            fields.keys,
        )
        assertTrue(fields.getValue("id").jsonObject.getValue("notNull").jsonPrimitive.content.toBoolean())
        assertTrue(fields.getValue("draftText").jsonObject.getValue("notNull").jsonPrimitive.content.toBoolean())
        listOf("modelId", "systemPromptId", "conversationSettingsJson", "draftAttachments")
            .forEach { column ->
                assertFalse(fields.getValue(column).jsonObject.containsKey("notNull"))
            }
        assertEquals(
            listOf("id"),
            entity.getValue("primaryKey").jsonObject
                .getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(
                    directory,
                    "app/schemas/com.newoether.agora.data.local.ChatDatabase/24.json",
                ),
                File(
                    directory,
                    "schemas/com.newoether.agora.data.local.ChatDatabase/24.json",
                ),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 24")
    }
}
