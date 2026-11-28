package com.newoether.agora.data.local.migration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration31To32Test {
    @Test fun existingMessageAndUnknownTimingSurviveAdditiveMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "generation-speed-migration"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, text TEXT NOT NULL, outputTokenCount INTEGER)")
                        db.execSQL("INSERT INTO messages VALUES ('kept', 'original answer', 80)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        MIGRATION_31_32.migrate(db)
                    }
                }).build(),
        )
        try {
            open(31).use { it.writableDatabase }
            open(32).use { helper ->
                val db = helper.writableDatabase
                db.query("SELECT id,text,outputTokenCount,generationDurationMs FROM messages").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("original answer", it.getString(1))
                    assertEquals(80, it.getInt(2))
                    assertTrue(it.isNull(3))
                }
                db.execSQL("UPDATE messages SET generationDurationMs=4000 WHERE id='kept'")
            }
            open(32).use { helper ->
                helper.readableDatabase.query("SELECT generationDurationMs FROM messages WHERE id='kept'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(4000L, it.getLong(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
