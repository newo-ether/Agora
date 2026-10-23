package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration36To37Test {
    @Test
    fun `migration adds an empty isolated document_embeddings table and preserves existing data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "document-embeddings-migration"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Minimal pre-existing message-embedding row to prove isolation (untouched).
                        db.execSQL(
                            "CREATE TABLE embeddings (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "messageId TEXT NOT NULL, modelId TEXT NOT NULL, embedding BLOB NOT NULL, " +
                                "chunkText TEXT NOT NULL, dimension INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "INSERT INTO embeddings (messageId, modelId, embedding, chunkText, dimension) " +
                                "VALUES ('m1','model-a', X'00000000', 'hi', 1)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_36_37.migrate(db)
                    }
                }).build(),
        )
        try {
            open(36).use { it.writableDatabase }
            open(37).use { helper ->
                val db = helper.readableDatabase
                // New table exists and is empty.
                db.query("SELECT COUNT(*) FROM document_embeddings").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                // Can insert and read a corpus row.
                db.execSQL(
                    "INSERT INTO document_embeddings " +
                        "(corpus, docId, title, chunkIndex, modelId, embedding, dimension, chunkText, sourceFingerprint) " +
                        "VALUES ('manual','scanner','Network Scanner',0,'model-a', X'3f800000', 1, 'wifi scan', 'fp1')",
                )
                db.query(
                    "SELECT corpus,docId,title,modelId FROM document_embeddings WHERE docId='scanner'",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("manual", it.getString(0))
                    assertEquals("scanner", it.getString(1))
                    assertEquals("Network Scanner", it.getString(2))
                    assertEquals("model-a", it.getString(3))
                }
                // The pre-existing message embedding is untouched (isolation).
                db.query("SELECT messageId FROM embeddings").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("m1", it.getString(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `room schema and database registration include migration 36 to 37`() {
        val root = locateRepositoryRoot()
        val schema = File(
            root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/37.json",
        ).readText()
        val database = File(
            root,
            "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
        ).readText()
        assertTrue(schema.contains("\"version\": 37"))
        assertTrue(schema.contains("document_embeddings"))
        assertTrue(database.contains("MIGRATION_36_37,"))
        assertTrue(database.contains("CURRENT_VERSION = 40"))
    }

    private fun locateRepositoryRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(directory, "app/schemas").isDirectory) return directory
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate repository root")
    }
}
