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
class Migration48To49Test {
    @Test fun backfillsHistoryOnceAndPreservesRowsWrittenSinceV48() = runBlocking {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "app/schemas").isDirectory) root = requireNotNull(root.parentFile)
        val schema = Json.parseToJsonElement(File(root,
            "app/schemas/com.newoether.agora.data.local.ChatDatabase/48.json").readText())
            .jsonObject.getValue("database").jsonObject
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(ChatDatabase.DB_NAME).callback(object : SupportSQLiteOpenHelper.Callback(48) {
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
                        VALUES ('chat','Chat',1,1,0,'user',0,'',0,1)""")
                    db.execSQL("""INSERT INTO conversations
                        (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt,taskId)
                        VALUES ('task','Task',1,1,0,'task',0,'',0,1,'t1')""")
                    db.execSQL("""INSERT INTO conversations
                        (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt,parentConversationId)
                        VALUES ('sub','Subagent',1,1,0,'user',0,'',0,1,'chat')""")
                    for (id in listOf("chat", "task", "sub")) {
                        db.execSQL("""INSERT INTO runs
                            (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                            VALUES ('run-$id','$id','COMPLETED',NULL,0,1,1,1,'MODEL_COMPLETED',0)""")
                    }
                    // Two runs in one bucket; the second reports a zero cache split.
                    db.execSQL(message("m1", "chat", "run-chat", 1, 100, 80, 40, 20, "OpenAI:gpt-5"))
                    db.execSQL(message("m2", "chat", "run-chat", 1, 50, 40, 0, 10, "OpenAI:gpt-5"))
                    // User rows carry no usage and must not become ledger rows.
                    db.execSQL("""INSERT INTO messages
                        (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence)
                        VALUES ('m3','chat','hi','',0,'SUCCESS','USER',1900000,'run-chat',3)""")
                    // Task and subagent attribution comes from the conversation row.
                    db.execSQL(message("m4", "task", "run-task", 3, 200, 150, 100, 50, "Anthropic:claude-4"))
                    db.execSQL(message("m5", "sub", "run-sub", 3, 30, 20, null, 10, "Anthropic:claude-4"))
                    // At/after the earliest ledger bucket: already recorded at request time, never backfilled.
                    db.execSQL(message("m6", "chat", "run-chat", 10, 55, 50, 25, 5, "OpenAI:gpt-5"))
                    db.execSQL("""INSERT INTO usage_ledger
                        (bucket,provider,model,source,requests,totalTokens,inputTokens,cachedInputTokens,
                            outputTokens,cacheReportedInputTokens)
                        VALUES (10,'OpenAI','OpenAI:gpt-5','chat',1,999,900,0,99,900)""")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            helper.use { it.writableDatabase }
            val database = ChatDatabase.build(context)
            try {
                assertEquals(ChatDatabase.CURRENT_VERSION, database.openHelper.writableDatabase.version)
                val rows = database.usageLedgerDao().all().associateBy { it.bucket to it.source }
                assertEquals(rows.keys.toString(), 4, rows.size)

                val chat = rows.getValue(1L to UsageLedgerSource.CHAT)
                assertEquals("OpenAI", chat.provider)
                assertEquals("OpenAI:gpt-5", chat.model)
                assertEquals(2L, chat.requests)
                assertEquals(150L, chat.totalTokens)
                assertEquals(120L, chat.inputTokens)
                assertEquals(40L, chat.cachedInputTokens)
                assertEquals(30L, chat.outputTokens)
                assertEquals(120L, chat.cacheReportedInputTokens)

                val task = rows.getValue(3L to UsageLedgerSource.TASK)
                assertEquals(1L, task.requests)
                assertEquals(200L, task.totalTokens)
                assertEquals(150L, task.cacheReportedInputTokens)

                val subagent = rows.getValue(3L to UsageLedgerSource.SUBAGENT)
                assertEquals("Anthropic", subagent.provider)
                assertEquals(1L, subagent.requests)
                assertEquals(30L, subagent.totalTokens)
                assertEquals(0L, subagent.cacheReportedInputTokens)

                assertEquals(UsageLedgerRow(
                    bucket = 10, provider = "OpenAI", model = "OpenAI:gpt-5",
                    source = UsageLedgerSource.CHAT, requests = 1, totalTokens = 999,
                    inputTokens = 900, cachedInputTokens = 0, outputTokens = 99,
                    cacheReportedInputTokens = 900,
                ), rows.getValue(10L to UsageLedgerSource.CHAT))
            } finally { database.close() }
        } finally { context.deleteDatabase(ChatDatabase.DB_NAME) }
    }

    private fun message(
        id: String,
        conversationId: String,
        runId: String,
        bucket: Long,
        total: Int,
        input: Int,
        cached: Int?,
        output: Int,
        modelName: String,
    ) = buildString {
        append("""INSERT INTO messages
            (id,conversationId,text,images,tokenCount,inputTokenCount,cachedInputTokenCount,
                outputTokenCount,status,participant,timestamp,modelName,runId,runSequence)
            VALUES ('$id','$conversationId','reply','',$total,$input,${cached ?: "NULL"},
                $output,'SUCCESS','MODEL',${bucket * 900_000 + 100},'$modelName','$runId',0)""")
    }
}
