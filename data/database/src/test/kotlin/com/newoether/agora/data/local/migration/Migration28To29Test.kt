package com.newoether.agora.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Migration28To29Test {
    @Test
    fun migrationCreatesSemanticStateWithoutPayloadScans() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs

        MIGRATION_28_29.migrate(database)

        assertEquals(28, MIGRATION_28_29.startVersion)
        assertEquals(29, MIGRATION_28_29.endVersion)
        assertEquals(3, statements.size)
        val normalized = statements.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        assertEquals(1, Regex("CREATE TABLE IF NOT EXISTS semantic_index_ledger").findAll(normalized).count())
        assertEquals(1, Regex("CREATE TABLE IF NOT EXISTS semantic_index_work").findAll(normalized).count())
        assertEquals(1, Regex("CREATE INDEX IF NOT EXISTS index_semantic_index_work").findAll(normalized).count())
        listOf("messages", "conversations", "embeddings", "runs", "new_chat").forEach { table ->
            assertFalse(normalized.contains(table, ignoreCase = true))
        }
    }

    @Test
    fun generatedRoomSchemaMatchesSemanticIndexContract() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(29, database.getValue("version").jsonPrimitive.content.toInt())
        val entities = database.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }
        val ledger = entities.getValue("semantic_index_ledger").jsonObject
        val work = entities.getValue("semantic_index_work").jsonObject

        assertEquals(
            setOf("modelId", "state", "sourceRevision", "completedRevision", "updatedAt"),
            fields(ledger),
        )
        assertEquals(
            listOf("modelId"),
            ledger.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals(
            setOf("modelId", "messageId", "sourceFingerprint", "sourceRevision", "updatedAt"),
            fields(work),
        )
        assertEquals(
            listOf("modelId", "messageId"),
            work.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
        val index = work.getValue("indices").jsonArray.single().jsonObject
        assertEquals(
            listOf("modelId", "sourceRevision", "messageId"),
            index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        val foreignKey = work.getValue("foreignKeys").jsonArray.single().jsonObject
        assertEquals("semantic_index_ledger", foreignKey.getValue("table").jsonPrimitive.content)
        assertEquals("CASCADE", foreignKey.getValue("onDelete").jsonPrimitive.content)
        assertEquals(
            listOf("modelId"),
            foreignKey.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun fields(entity: kotlinx.serialization.json.JsonObject): Set<String> =
        entity.getValue("fields").jsonArray.mapTo(mutableSetOf()) {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/schemas/com.newoether.agora.data.local.ChatDatabase/29.json"),
                File(directory, "schemas/com.newoether.agora.data.local.ChatDatabase/29.json"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 29")
    }
}
