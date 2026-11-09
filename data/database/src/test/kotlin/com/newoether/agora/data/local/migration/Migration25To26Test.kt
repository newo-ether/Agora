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

class Migration25To26Test {
    @Test
    fun migrationAddsNullableCacheWriteTokenColumn() {
        val database = mockk<SupportSQLiteDatabase>()
        val statement = slot<String>()
        every { database.execSQL(capture(statement)) } just Runs

        MIGRATION_25_26.migrate(database)

        assertEquals(25, MIGRATION_25_26.startVersion)
        assertEquals(26, MIGRATION_25_26.endVersion)
        assertEquals(
            "ALTER TABLE messages ADD COLUMN cacheWriteInputTokenCount INTEGER",
            statement.captured,
        )
    }

    @Test
    fun generatedRoomSchemaContainsNullableCacheWriteTokenColumn() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(26, database.getValue("version").jsonPrimitive.content.toInt())
        val messages = database.getValue("entities").jsonArray.single {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == "messages"
        }.jsonObject
        val field = messages.getValue("fields").jsonArray.single {
            it.jsonObject.getValue("columnName").jsonPrimitive.content ==
                "cacheWriteInputTokenCount"
        }.jsonObject
        assertEquals("INTEGER", field.getValue("affinity").jsonPrimitive.content)
        assertFalse(field.containsKey("notNull"))
    }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(
                    directory,
                    "app/schemas/com.newoether.agora.data.local.ChatDatabase/26.json",
                ),
                File(
                    directory,
                    "schemas/com.newoether.agora.data.local.ChatDatabase/26.json",
                ),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 26")
    }
}
