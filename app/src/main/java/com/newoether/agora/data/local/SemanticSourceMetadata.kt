package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/** Derived search input. Maintenance never needs to reopen the original message body. */
@Entity(
    tableName = "semantic_sources",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["conversationId", "messageId"])],
)
data class SemanticSourceEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val eligible: Boolean,
    val sourcePrefix: ByteArray,
    val sourceFingerprint: String?,
    /** SQLite LENGTH(TEXT) semantics, including its first-NUL boundary. */
    val searchLength: Int,
    val historyEligible: Boolean,
)

/** The revision changes only when this conversation's embedding input or eligibility changes. */
@Entity(
    tableName = "semantic_conversations",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SemanticConversationEntity(
    @PrimaryKey val conversationId: String,
    val sourceRevision: Long = 0,
    val eligibleCount: Int = 0,
    val cacheEligible: Boolean,
)

@Entity(
    tableName = "semantic_model_conversations",
    primaryKeys = ["modelId", "conversationId"],
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["conversationId"])],
)
data class SemanticModelConversationEntity(
    val modelId: String,
    val conversationId: String,
    val cachedCount: Int = 0,
    val indexedRevision: Long = -1,
)

/** One-time legacy bootstrap cursor. COMPLETE never becomes PENDING during ordinary writes. */
@Entity(tableName = "semantic_metadata_state")
data class SemanticMetadataStateEntity(
    @PrimaryKey val id: Int = 0,
    val complete: Boolean,
    val afterMessageId: String? = null,
)

data class SemanticMessageIdentity(val id: String, val conversationId: String, val participant: Participant)
data class SemanticEmbeddingIdentity(val messageId: String, val modelId: String)
data class SemanticCountSummary(val modelId: String?, val total: Int, val cached: Int)
data class SemanticLegacySourceRow(
    val messageId: String,
    val conversationId: String,
    val participant: Participant,
    val prefix: ByteArray,
    val searchLength: Int,
    val nonempty: Boolean,
)

internal fun semanticSource(
    messageId: String,
    conversationId: String,
    participant: Participant,
    text: String,
): SemanticSourceEntity {
    // SQLite LIKE folds only ASCII; '_' in the historical patterns matches any one character.
    val synthetic = listOf("tool", "result", "compact").any { prefix ->
        messageId.length > prefix.length && prefix.indices.all { index ->
            val char = messageId[index]
            (if (char in 'A'..'Z') char + 32 else char) == prefix[index]
        }
    }
    val ordinary = text.isNotEmpty() &&
        (participant == Participant.USER || participant == Participant.MODEL)
    val eligible = ordinary && !synthetic
    // Keep the next UTF-16 unit so UTF-8 persistence cannot split a surrogate pair.
    // The provider input and fingerprint still use exactly the historical take(8000) boundary.
    val prefix = if (eligible) text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH + 1) else ""
    val end = text.indexOf('\u0000').takeIf { it >= 0 } ?: text.length
    return SemanticSourceEntity(
        messageId, conversationId, eligible, prefix.toByteArray(Charsets.UTF_8),
        if (eligible) semanticSourceFingerprint(prefix) else null,
        if (ordinary) text.codePointCount(0, end) else 0,
        ordinary && listOf("tool_", "result_", "compact_").none(messageId::startsWith),
    )
}
