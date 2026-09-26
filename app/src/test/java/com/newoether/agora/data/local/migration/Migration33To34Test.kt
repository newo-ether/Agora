package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration33To34Test {
    @Test
    fun `existing tasks keep their literal prompt and gain an empty prompt reference`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task-system-prompt-migration"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE tasks (id TEXT PRIMARY KEY, name TEXT NOT NULL, " +
                                "prompt TEXT NOT NULL, systemPrompt TEXT)",
                        )
                        db.execSQL("INSERT INTO tasks VALUES ('kept', 'Daily', 'Run it', 'Literal text')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_33_34.migrate(db)
                    }
                }).build(),
        )
        try {
            open(33).use { it.writableDatabase }
            open(34).use { helper ->
                helper.readableDatabase.query(
                    "SELECT id,systemPrompt,systemPromptId FROM tasks",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("Literal text", it.getString(1))
                    assertNull(it.getString(2))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `room schema and database registration include migration 33 to 34`() {
        val root = locateRepositoryRoot()
        val schema = File(
            root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/34.json",
        ).readText()
        val database = File(
            root,
            "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt",
        ).readText()
        assertTrue(schema.contains("\"version\": 34"))
        assertTrue(schema.contains("\"tableName\": \"tasks\""))
        assertTrue(schema.contains("\"fieldPath\": \"systemPromptId\""))
        assertTrue(database.contains("MIGRATION_33_34"))
        assertTrue(
            Regex("MIGRATION_32_33,\\s*MIGRATION_33_34,").containsMatchIn(database),
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
