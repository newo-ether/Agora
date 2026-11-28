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
class Migration34To35Test {
    @Test
    fun `existing messages keep their text and gain no source`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "message-source-migration"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, text TEXT NOT NULL)")
                        db.execSQL("INSERT INTO messages VALUES ('kept', 'Old prompt')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_34_35.migrate(db)
                    }
                }).build(),
        )
        try {
            open(34).use { it.writableDatabase }
            open(35).use { helper ->
                helper.readableDatabase.query("SELECT id,text,sourceJson FROM messages").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("Old prompt", it.getString(1))
                    assertNull(it.getString(2))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `room schema and database registration include migration 34 to 35`() {
        val root = locateRepositoryRoot()
        val schema = File(
            root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/35.json",
        ).readText()
        val database = File(
            root,
            "data/database/src/main/kotlin/com/newoether/agora/data/local/ChatDatabase.kt",
        ).readText()
        assertTrue(schema.contains("\"version\": 35"))
        assertTrue(schema.contains("\"fieldPath\": \"sourceJson\""))
        assertTrue(Regex("MIGRATION_33_34,\\s*MIGRATION_34_35,").containsMatchIn(database))
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
