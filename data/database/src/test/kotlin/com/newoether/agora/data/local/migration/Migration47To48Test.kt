package com.newoether.agora.data.local.migration

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.UsageLedgerRow
import com.newoether.agora.data.local.UsageLedgerSource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Migration47To48Test {
    @Test fun upgradeAddsAnEmptyLedgerThatAccumulatesAndSurvivesConversationDeletion() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/47.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(ChatDatabase.DB_NAME).callback(object : SupportSQLiteOpenHelper.Callback(47) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { entry ->
                        val table = entry.jsonObject
                        val name = table.getValue("tableName").jsonPrimitive.content
                        db.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        table["indices"]?.jsonArray?.forEach {
                            db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                        }
                    }
                    db.execSQL("""INSERT INTO conversations
                        (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt)
                        VALUES ('owner','Keep',1,1,0,'user',0,'',0,1)""")
                    db.execSQL("""INSERT INTO runs
                        (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                        VALUES ('run','owner','COMPLETED',NULL,0,1,1,1,'MODEL_COMPLETED',0)""")
                    db.execSQL("""INSERT INTO messages
                        (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence)
                        VALUES ('m','owner','hi','',42,'SUCCESS','MODEL',1,'run',0)""")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            helper.use { it.writableDatabase }
            val database = ChatDatabase.build(context)
            try {
                assertEquals(48, database.openHelper.writableDatabase.version)
                val dao = database.usageLedgerDao()
                // History is not backfilled from existing messages.
                assertTrue(dao.all().isEmpty())

                val row = UsageLedgerRow(
                    bucket = 7, provider = "OpenAI", model = "OpenAI:gpt-x", source = UsageLedgerSource.CHAT,
                    requests = 1, totalTokens = 100, inputTokens = 80, cachedInputTokens = 40,
                    outputTokens = 20, cacheReportedInputTokens = 80,
                )
                dao.add(row)
                dao.add(row)
                dao.add(row.copy(source = UsageLedgerSource.TASK))
                assertEquals(null to null, dao.conversationKind("owner")?.let { it.taskId to it.parentConversationId })

                database.openHelper.writableDatabase.execSQL("DELETE FROM conversations WHERE id='owner'")
                val rows = dao.all().associateBy { it.source }
                assertEquals(2, rows.size)
                assertEquals(row.copy(requests = 2, totalTokens = 200, inputTokens = 160, cachedInputTokens = 80,
                    outputTokens = 40, cacheReportedInputTokens = 160), rows[UsageLedgerSource.CHAT])
                assertEquals(1L, rows.getValue(UsageLedgerSource.TASK).requests)
            } finally { database.close() }
        } finally { context.deleteDatabase(ChatDatabase.DB_NAME) }
    }
}
