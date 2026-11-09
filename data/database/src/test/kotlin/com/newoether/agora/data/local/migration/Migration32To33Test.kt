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
class Migration32To33Test {
    @Test
    fun `existing conversations receive a durable zero watermark`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "conversation-watermark-migration"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
                        db.execSQL("INSERT INTO conversations VALUES ('kept', 'Existing chat')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_32_33.migrate(db)
                    }
                }).build(),
        )
        try {
            open(32).use { it.writableDatabase }
            open(33).use { helper ->
                helper.readableDatabase.query(
                    "SELECT id,title,dataChangedAt FROM conversations",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("Existing chat", it.getString(1))
                    assertEquals(0L, it.getLong(2))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `room schema and database registration include migration 32 to 33`() {
        val root = locateRepositoryRoot()
        val schema = File(
            root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/33.json",
        ).readText()
        val database = File(
            root,
            "data/database/src/main/kotlin/com/newoether/agora/data/local/ChatDatabase.kt",
        ).readText()
        assertTrue(schema.contains("\"version\": 33"))
        assertTrue(schema.contains("\"fieldPath\": \"dataChangedAt\""))
        assertTrue(schema.contains("\"notNull\": true"))
        assertTrue(database.contains("MIGRATION_32_33"))
        assertTrue(
            Regex("MIGRATION_31_32,\\s*MIGRATION_32_33,").containsMatchIn(database),
        )
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
