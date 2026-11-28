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

class Migration27To28Test {
    @Test
    fun migrationCreatesDebtSchemaAndThreeReconcileMarkersWithoutPayloadScans() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs

        MIGRATION_27_28.migrate(database)

        assertEquals(27, MIGRATION_27_28.startVersion)
        assertEquals(28, MIGRATION_27_28.endVersion)
        assertEquals(5, statements.size)
        assertEquals(
            listOf("ATTACHMENT_ORPHANS", "EMBEDDING_ORPHANS", "RUN_BRANCHES"),
            statements.drop(2).map { statement ->
                Regex("VALUES \\('([^']+)'", RegexOption.IGNORE_CASE)
                    .find(statement)?.groupValues?.get(1)
            },
        )
        val normalized = statements.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        assertEquals(1, Regex("CREATE TABLE IF NOT EXISTS maintenance_debt").findAll(normalized).count())
        assertEquals(1, Regex("CREATE INDEX IF NOT EXISTS index_maintenance_debt").findAll(normalized).count())
        assertEquals(3, Regex("INSERT OR IGNORE INTO maintenance_debt").findAll(normalized).count())
        listOf("messages", "conversations", "embeddings", "runs", "new_chat").forEach { table ->
            assertFalse(normalized.contains(table, ignoreCase = true))
        }
    }

    @Test
    fun generatedRoomSchemaMatchesMaintenanceDebtContract() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(28, database.getValue("version").jsonPrimitive.content.toInt())
        val entity = database.getValue("entities").jsonArray.single {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == "maintenance_debt"
        }.jsonObject
        val fields = entity.getValue("fields").jsonArray.associateBy {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

        assertEquals(
            setOf("kind", "identity", "state", "revision", "updatedAt", "claimId", "claimedAt"),
            fields.keys,
        )
        assertEquals(
            listOf("kind", "identity"),
            entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
                .map { it.jsonPrimitive.content },
        )
        val index = entity.getValue("indices").jsonArray.single().jsonObject
        assertEquals(
            listOf("state", "updatedAt", "kind", "identity"),
            index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(index.getValue("unique").jsonPrimitive.content.toBoolean())
    }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/schemas/com.newoether.agora.data.local.ChatDatabase/28.json"),
                File(directory, "schemas/com.newoether.agora.data.local.ChatDatabase/28.json"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 28")
    }
}
