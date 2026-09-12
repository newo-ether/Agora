package com.newoether.agora.data.local

import androidx.room.*
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageConverters {
    @TypeConverter
    fun fromParticipant(value: Participant) = value.name
    @TypeConverter
    fun toParticipant(value: String) = Participant.valueOf(value)

    @TypeConverter
    fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter
    fun toStatus(value: String) = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromRunStatus(value: RunStatus) = value.name
    @TypeConverter
    fun toRunStatus(value: String) = RunStatus.valueOf(value)

    @TypeConverter
    fun fromRunEndReason(value: RunEndReason?) = value?.name
    @TypeConverter
    fun toRunEndReason(value: String?) = value?.let(RunEndReason::valueOf)

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return if (value != null) Json.encodeToString(value) else ""
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            Json.decodeFromString<List<String>>(value)
        } catch (_: Exception) {
            // Backward compatibility: old format used "|||" delimiter
            value.split("|||")
        }
    }

    @TypeConverter
    fun fromSmsDraftStatus(value: SmsDraftStatus) = value.name
    @TypeConverter
    fun toSmsDraftStatus(value: String) = SmsDraftStatus.valueOf(value)
}
@Entity(tableName = "new_chat_persist")
data class NewChatPersistEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val modelId: String? = null,
    val systemPromptId: String? = null,
    val conversationSettingsJson: String? = null,
    val draftText: String = "",
    val draftAttachments: String? = null,
) {
    init {
        require(id == SINGLETON_ID)
    }

    companion object {
        const val SINGLETON_ID = 0
    }
}

@Entity(
    tableName = "conversation_settings_transfer",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ConversationSettingsTransferEntity(
    @PrimaryKey val conversationId: String,
    val settingsJson: String? = null,
)

@Entity(tableName = "conversation_settings_import_transfer")
data class ConversationSettingsImportTransferEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val transferId: String,
    val settingsJson: String,
    val mode: String,
) {
    init {
        require(id == SINGLETON_ID)
        require(transferId.isNotBlank())
        require(mode == MODE_MERGE || mode == MODE_REPLACE)
    }

    companion object {
        const val SINGLETON_ID = 0
        const val MODE_MERGE = "MERGE"
        const val MODE_REPLACE = "REPLACE"
    }
}

@Entity(tableName = "conversations", indices = [Index(value = ["taskId"])])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val selectedBranchesJson: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    /** Which task spawned this conversation; null = ordinary user conversation. */
    val taskId: String? = null,
    /** How this conversation was created: "user" | "task" | "loop". */
    val origin: String = "user",
    /** Legacy import provenance: true when an orphaned task execution was promoted into the
     * ordinary conversation list. Retained for archive compatibility; it drives no UI behavior. */
    val graduated: Boolean = false,
    /** Unsent composer text for per-conversation draft persistence. */
    val draftText: String = "",
    /** JSON-serialized list of [com.newoether.agora.model.SelectedAttachment]; null = no draft attachments. */
    val draftAttachments: String? = null,
    /** Run-level branch selections. Message-level selections remain for legacy compatibility. */
    val selectedRunBranchesJson: String? = null,
    /** True after a completed model generation until this conversation becomes the open target. */
    val hasUnreadGeneration: Boolean = false,
)

/** A saved automation: a prompt + schedule that fans out a fresh conversation on each run. */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Replayed as the first user message of every execution. */
    val prompt: String,
    val systemPrompt: String? = null,
    /** null = use the app default model. */
    val modelId: String? = null,
    /** 5-field cron expression driving a RECURRING schedule; blank for a one-shot. */
    val cronExpr: String,
    /** One-shot fire time. A 5-field cron has no year, so "once on a date" cannot be a cron —
     *  it is an absolute epoch instead, and the task disables itself after it fires.
     *  Mutually exclusive with [cronExpr]: exactly one of the two is set on a scheduled task. */
    val runAt: Long? = null,
    /** Local derived value; imports clear it until the user explicitly re-enables the task. */
    val nextRunAt: Long,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)

/** A loop attached to a single conversation: periodically re-injects a user turn in-context. */
@Entity(
    tableName = "loops",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LoopEntity(
    @PrimaryKey val conversationId: String,
    val intervalMs: Long,
    /** null = a generic "continue" turn. */
    val prompt: String? = null,
    val nextFireAt: Long,
    val cycleCount: Int = 0,
    /**
     * Safety ceiling. The column remains nullable for schema/backward compatibility, but domain
     * code normalizes legacy nulls to the bounded LoopPolicy default.
     */
    val maxCycles: Int? = null,
    val active: Boolean = true,
    /** Configuration generation used to keep a stale in-flight cycle from overwriting stop/restart. */
    val revision: Long = 0L
)

@Entity(
    tableName = "embeddings",
    indices = [
        Index(value = ["messageId", "modelId"], unique = true),
        Index(value = ["modelId"]),
    ]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val modelId: String,
    val embedding: ByteArray,
    val chunkText: String,
    val dimension: Int,
    /** Canonical embedded-source fingerprint; null only for embeddings created before schema v30. */
    val sourceFingerprint: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id && messageId == other.messageId && modelId == other.modelId
            && embedding.contentEquals(other.embedding) && chunkText == other.chunkText &&
            dimension == other.dimension && sourceFingerprint == other.sourceFingerprint
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + dimension
        result = 31 * result + (sourceFingerprint?.hashCode() ?: 0)
        return result
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["runId"]),
        Index(value = ["id", "images", "attachmentMeta"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val inputTokenCount: Int? = null,
    val cachedInputTokenCount: Int? = null,
    val cacheWriteInputTokenCount: Int? = null,
    val uncachedInputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val reasoningTokenCount: Int? = null,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCallJson: String? = null,
    val attachmentMeta: String? = null,
    val runId: String,
    val runSequence: Long = UNASSIGNED_RUN_SEQUENCE,
    /** Non-null only for visible user input; null for model/tool/result rows. */
    val consumedAtPass: Int? = null,
) {
    companion object {
        const val UNASSIGNED_RUN_SEQUENCE = -1L
    }
}

/**
 * Partial Room entity used for durable streaming checkpoints.
 *
 * Keeping only fields that can change while a model is generating prevents a checkpoint from
 * overwriting stable relationship/model metadata. [ChatDao.updateMessageCheckpoint] is an UPDATE,
 * not an upsert: if a concurrent delete removed the placeholder, streaming must not resurrect it.
 */
data class MessageStreamCheckpoint(
    val id: String,
    val text: String,
    val images: List<String>,
    val thoughts: String?,
    val thoughtTitle: String?,
    val tokenCount: Int,
    val inputTokenCount: Int?,
    val cachedInputTokenCount: Int?,
    val cacheWriteInputTokenCount: Int?,
    val uncachedInputTokenCount: Int?,
    val outputTokenCount: Int?,
    val reasoningTokenCount: Int?,
    val status: MessageStatus,
    val thoughtTimeMs: Long?,
    val toolCallJson: String?,
)

/** Attachment-only projection used by sweeps and media export.
 *
 * These callers do not need message bodies, thoughts, or tool payloads. Returning a full
 * [MessageEntity] for every row can otherwise expand a large database past Android's heap limit.
 */
data class MessageAttachmentReference(
    val id: String,
    val images: List<String>,
    val attachmentMeta: String? = null,
)

/** Tool-payload-only projection used to archive generated image files without loading messages. */
data class MessageToolMediaReference(
    val id: String,
    val toolCallJson: String,
)

/** Draft-only projection used by the orphaned attachment sweep. */
data class ConversationDraftAttachmentReference(
    val id: String,
    val draftAttachments: String,
)

/** Singleton New Chat draft reference used by attachment reclamation. */
data class NewChatDraftAttachmentReference(
    val draftAttachments: String,
)

/** Minimal payload needed to generate an embedding. */
data class IndexableMessage(
    val id: String,
    val text: String,
)

data class RunGraphCommit(
    val messages: List<MessageEntity>,
    val messageSelections: Map<String?, String>,
    val runSelections: Map<String?, String>,
)

data class ToolRoundCommit(
    val messages: List<MessageEntity>,
    /** False only when the exact same complete round was already durable. */
    val inserted: Boolean,
)

/** SMS draft status. */
enum class SmsDraftStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
}

/** Heartbeat log entry stored in Room. */
@Entity(tableName = "heartbeat_logs")
data class HeartbeatLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val success: Boolean,
    val error: String? = null,
)

/** SMS message stored in Room. */
@Entity(tableName = "sms_messages")
data class SmsMessageEntity(
    @PrimaryKey val id: Long,
    val address: String,
    val date: Long,
    val preview: String,
    val body: String = "",
    val read: Boolean = false,
)

/** SMS sync state stored in Room. */
@Entity(tableName = "sms_sync_state")
data class SmsSyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lastSeenId: Long = 0L,
    val lastSyncEpochMs: Long = 0L,
    val lastAttemptEpochMs: Long = 0L,
    val unreadCount: Int = 0,
    val lastError: String? = null,
) {
    init {
        require(id == SINGLETON_ID)
    }

    companion object {
        const val SINGLETON_ID = 0
    }
}

/** SMS draft stored in Room. */
@Entity(tableName = "sms_drafts")
data class SmsDraftEntity(
    @PrimaryKey val id: String,
    val address: String,
    val body: String,
    val createdAtEpochMs: Long,
    val inReplyToSmsId: Long? = null,
    val status: SmsDraftStatus = SmsDraftStatus.PENDING,
    val lastError: String? = null,
)

/**
 * SMS awaiting the next heartbeat. Polled messages are queued here (capped FIFO)
 * so the heartbeat prompt can surface exactly the messages the AI has not seen
 * yet; the full body is fetched on demand by `read_sms` from the system provider.
 * The snapshot shown to the AI is removed only after a successful run.
 */
@Entity(tableName = "sms_pending")
data class SmsPendingEntity(
    @PrimaryKey val id: Long,
    val address: String,
    val date: Long,
    val preview: String,
    val read: Boolean = false,
)

/** Notification record stored in Room. */
@Entity(
    tableName = "notifications",
    indices = [Index(value = ["package_name"]), Index(value = ["posted_at"])]
)
data class NotificationRecordEntity(
    @PrimaryKey val id: String,
    val package_name: String,
    val app_label: String,
    val title: String,
    val text: String,
    val subtext: String? = null,
    val posted_at: Long,
    val category: String? = null,
    val preview: String,
)

/** Notification sync state stored in Room. */
@Entity(tableName = "notification_sync_state")
data class NotificationSyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lastSeenKey: String? = null,
    val pendingKeysJson: String = "[]",
    val recordsCount: Int = 0,
    val lastSyncEpochMs: Long = 0L,
    val lastAttemptEpochMs: Long = 0L,
    val lastError: String? = null,
) {
    init {
        require(id == SINGLETON_ID)
    }

    companion object {
        const val SINGLETON_ID = 0
    }
}

/** Pure validation/idempotency policy shared by the Room transaction and JVM tests. */
internal object ToolRoundCommitPolicy {
    fun canInsert(run: RunEntity, runId: String, expectedPass: Int): Boolean =
        expectedPass >= 0 &&
            run.id == runId &&
            run.status == RunStatus.ACTIVE &&
            run.activeSlot == 1 &&
            run.currentPass == expectedPass

    fun requireValidShape(messages: List<MessageEntity>): String {
        require(messages.size >= 2) {
            "A tool round requires a tool row and at least one result"
        }
        require(messages.map { it.id }.distinct().size == messages.size) {
            "A tool round cannot contain duplicate message ids"
        }
        val toolMessage = messages.first()
        val runId = toolMessage.runId
        require(runId.isNotBlank()) { "A tool round requires a durable Run" }
        require(messages.all { it.runId == runId }) { "One tool round cannot span Runs" }
        require(messages.all { it.conversationId == toolMessage.conversationId }) {
            "One tool round cannot span conversations"
        }
        require(toolMessage.participant == Participant.MODEL) {
            "A tool round must start with an assistant tool-call row"
        }
        require(!toolMessage.toolCallJson.isNullOrBlank()) {
            "A tool-call row requires protocol metadata"
        }
        require(messages.drop(1).all { result ->
            result.participant == Participant.USER &&
                result.parentId == toolMessage.id &&
                !result.toolCallJson.isNullOrBlank()
        }) {
            "Every tool result must be complete and attached to the tool-call row"
        }
        val requestCalls = decodeSegments(toolMessage)
            .filter { segment -> segment.type == "tool" }
        require(requestCalls.isNotEmpty()) { "A tool-call row requires at least one call" }
        val requestIds = requestCalls.map { call ->
            requireNotNull(call.toolCallId?.takeIf { it.isNotBlank() }) {
                "Every tool request requires an id"
            }
        }
        require(requestIds.distinct().size == requestIds.size) {
            "A tool round cannot contain duplicate call ids"
        }
        val resultIds = messages.drop(1).map { resultMessage ->
            val result = decodeSegments(resultMessage)
                .filter { segment -> segment.type == "tool" }
                .singleOrNull()
                ?: throw IllegalArgumentException(
                    "Each result row must contain exactly one tool result",
                )
            require(result.toolResult != null) { "A tool result payload cannot be missing" }
            requireNotNull(result.toolCallId?.takeIf { it.isNotBlank() }) {
                "Every tool result requires a call id"
            }
        }
        require(resultIds == requestIds) {
            "Tool request and result ids must form one complete ordered batch"
        }
        return runId
    }

    private fun decodeSegments(message: MessageEntity): List<MessageSegment> = try {
        Json.decodeFromString(requireNotNull(message.toolCallJson))
    } catch (error: Exception) {
        throw IllegalArgumentException(
            "Tool protocol metadata is not a valid segment list",
            error,
        )
    }

    /** Null means no row exists yet; any partial or conflicting replay fails closed. */
    fun resolveExactReplay(
        proposed: List<MessageEntity>,
        existing: List<MessageEntity>,
    ): List<MessageEntity>? {
        if (existing.isEmpty()) return null
        check(existing.size == proposed.size) {
            "A partially persisted tool round cannot be replayed"
        }
        val existingById = existing.associateBy { it.id }
        val ordered = proposed.map { message ->
            checkNotNull(existingById[message.id]) {
                "A conflicting message id exists for the tool round"
            }
        }
        check(ordered.zip(proposed).all { (durable, requested) ->
            durable.copy(runSequence = MessageEntity.UNASSIGNED_RUN_SEQUENCE) ==
                requested.copy(runSequence = MessageEntity.UNASSIGNED_RUN_SEQUENCE)
        }) {
            "A conflicting tool round replay was rejected"
        }
        return ordered
    }
}
