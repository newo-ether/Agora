package com.newoether.agora.data.repository

import androidx.room.Transactor
import androidx.room.useReaderConnection
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.withChatDatabaseReadSnapshot

/** The production context selects the independent pool; DAO-only tests need no second database. */
internal suspend fun <T> ConversationRepository.readSnapshot(block: suspend (ChatDao) -> T): T {
    snapshotContext?.let { return withChatDatabaseReadSnapshot(it, block = block) }
    if (database == null) return block(chatDao)
    return database.useReaderConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) { block(chatDao) }
    }
}
