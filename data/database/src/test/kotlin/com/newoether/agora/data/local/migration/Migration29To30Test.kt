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
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration29To30Test {
    @Test
    fun attachmentIndexUpgradePreservesEntityPayloadsAndOnlyCreatesOneIndex() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs
        MIGRATION_30_31.migrate(database)
        assertEquals(30, MIGRATION_30_31.startVersion)
        assertEquals(31, MIGRATION_30_31.endVersion)
        assertEquals(listOf(
            "CREATE INDEX index_messages_id_images_attachmentMeta ON messages(id, images, attachmentMeta)",
        ), statements)
        val oldDatabase = Json.parseToJsonElement(locateSchema(30).readText()).jsonObject.getValue("database").jsonObject
        val newDatabase = Json.parseToJsonElement(locateSchema(31).readText()).jsonObject.getValue("database").jsonObject
        val oldEntities = oldDatabase.getValue("entities").jsonArray
        val newEntities = newDatabase.getValue("entities").jsonArray
        assertEquals(oldEntities.size, newEntities.size)
        oldEntities.zip(newEntities).forEach { (old, new) ->
            assertEquals(old.jsonObject["fields"], new.jsonObject["fields"])
            assertEquals(old.jsonObject["foreignKeys"], new.jsonObject["foreignKeys"])
        }
        val messages = newEntities.single { it.jsonObject["tableName"]?.jsonPrimitive?.content == "messages" }.jsonObject
        assertTrue(messages.getValue("indices").jsonArray.any {
            it.jsonObject.getValue("columnNames").jsonArray.map { column -> column.jsonPrimitive.content } ==
                listOf("id", "images", "attachmentMeta")
        })
    }

    @Test
    fun migrationPreservesPayloadsAndInitializesReconcileGeneration() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs

        MIGRATION_29_30.migrate(database)

        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
        assertEquals(3, statements.size)
        val normalized = statements.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        assertTrue(normalized.contains("ADD COLUMN reconcileRevision INTEGER NOT NULL DEFAULT 0"))
        assertTrue(normalized.contains("SET reconcileRevision = sourceRevision"))
        assertTrue(normalized.contains("WHERE state = 'NEEDS_RECONCILE'"))
        assertTrue(normalized.contains("embeddings ADD COLUMN sourceFingerprint TEXT"))
    }

    @Test
    fun generatedRoomSchemaContainsReconcileAndFingerprintColumns() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(30, database.getValue("version").jsonPrimitive.content.toInt())
        val entities = database.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }

        assertTrue("reconcileRevision" in fields(entities.getValue("semantic_index_ledger").jsonObject))
        assertTrue("sourceFingerprint" in fields(entities.getValue("embeddings").jsonObject))
    }

    private fun fields(entity: kotlinx.serialization.json.JsonObject): Set<String> =
        entity.getValue("fields").jsonArray.mapTo(mutableSetOf()) {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

    private fun locateSchema(version: Int = 30): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/schemas/com.newoether.agora.data.local.ChatDatabase/$version.json"),
                File(directory, "schemas/com.newoether.agora.data.local.ChatDatabase/$version.json"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema $version")
    }
}
