package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.TaskEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One record of the export snapshot, emitted in archive-relevant order. */
internal sealed interface SnapshotRecord {
    data class Conversation(val entity: ChatEntity) : SnapshotRecord
    data class Run(val entity: RunEntity) : SnapshotRecord
    data class Message(val entity: MessageEntity) : SnapshotRecord
    data class Loop(val entity: LoopEntity) : SnapshotRecord
    data class Task(val entity: TaskEntity) : SnapshotRecord
}

/** Streams an immutable conversation graph with at most one full message page retained. */
internal class ConversationExportSnapshotReader(private val context: Context) {
    companion object {
        private const val MESSAGE_PAGE_SIZE = 64
    }

    /** [includeBody] can reuse an unchanged archive entry after reading only its header. */
    suspend fun readSnapshot(
        includeBody: suspend (ChatEntity) -> Boolean = { true },
        onRecord: suspend (SnapshotRecord) -> Unit,
    ) {
        withChatDatabaseReadSnapshot(context, background = true) { snapshotDao ->
            for (conversation in snapshotDao.getAllConversationsList()) {
                currentCoroutineContext().ensureActive()
                onRecord(SnapshotRecord.Conversation(conversation))
                if (!includeBody(conversation)) continue
                for (run in snapshotDao.getRunsForConversationSnapshot(conversation.id)) {
                    currentCoroutineContext().ensureActive()
                    onRecord(SnapshotRecord.Run(run))
                }
                var afterMessageId: String? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = snapshotDao.getConversationMessagesPage(
                        conversation.id,
                        afterMessageId,
                        MESSAGE_PAGE_SIZE,
                    )
                    if (page.isEmpty()) break
                    for (message in page) {
                        currentCoroutineContext().ensureActive()
                        onRecord(SnapshotRecord.Message(message))
                    }
                    afterMessageId = page.last().id
                    if (page.size < MESSAGE_PAGE_SIZE) break
                }
                for (loop in snapshotDao.getLoopsForConversationSnapshot(conversation.id)) {
                    currentCoroutineContext().ensureActive()
                    onRecord(SnapshotRecord.Loop(loop))
                }
            }
            for (task in snapshotDao.getAllTasksList()) {
                currentCoroutineContext().ensureActive()
                onRecord(SnapshotRecord.Task(task))
            }
        }
    }
}
