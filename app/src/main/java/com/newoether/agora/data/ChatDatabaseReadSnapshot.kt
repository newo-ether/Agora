package com.newoether.agora.data

import android.content.Context
import android.os.Process
import androidx.room.Transactor
import androidx.room.useReaderConnection
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

private val snapshotThreadSequence = AtomicInteger()

/**
 * A separate Room pool and executor keep immutable WAL reads off the foreground write lane.
 * All reads in [block] must use its DAO; no file, Provider, or other external work belongs here.
 */
internal suspend fun <T> withChatDatabaseReadSnapshot(
    context: Context,
    background: Boolean = false,
    block: suspend (ChatDao) -> T,
): T = withContext(Dispatchers.IO) {
    val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread({
            if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "agora-snapshot-db-${snapshotThreadSequence.incrementAndGet()}")
    }
    val callerJob = currentCoroutineContext()[Job]
    var database: ChatDatabase? = null
    try {
        val reader = ChatDatabase.build(context, queryExecutor = executor, transactionExecutor = executor)
        database = reader
        return@withContext reader.useReaderConnection { connection ->
            // Room's connection scope must not detach this operation from its caller's Stop.
            withContext(callerJob ?: EmptyCoroutineContext) {
                connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                    block(reader.chatDao())
                }
            }
        }
    } finally {
        database?.close()
        executor.shutdown()
    }
}
