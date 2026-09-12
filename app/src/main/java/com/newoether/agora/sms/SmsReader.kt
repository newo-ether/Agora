package com.newoether.agora.data

/**
 * Interface for reading SMS messages.
 * Implementation only exists in fdroid flavor.
 */
interface SmsReader {
    /**
     * True when this build can ever read SMS — i.e. Android + `READ_SMS` declared
     * in the merged manifest (fdroid flavor only). Independent of the runtime grant.
     */
    fun isSupported(): Boolean

    /**
     * True when [isSupported] and the user has granted `READ_SMS` at runtime.
     */
    fun hasPermission(): Boolean

    /**
     * Returns inbox messages with `_id > lastSeenId`, ordered by `_id` ascending,
     * capped at [limit]. Empty list if not supported or permission denied.
     */
    suspend fun readNewMessages(lastSeenId: Long, limit: Int): List<SmsMessageData>

    /**
     * Fetches a single SMS by its system `_id`. Null if not found or permission denied.
     */
    suspend fun readById(id: Long): SmsMessageData?

    /**
     * Full-text search across inbox address + body, newest first, capped at [limit].
     */
    suspend fun search(query: String, limit: Int): List<SmsMessageData>

    /**
     * The current maximum inbox `_id`. Used to seed the sync high-water mark on first
     * enable so existing inbox history is not dumped into the pending queue.
     */
    suspend fun currentMaxInboxId(): Long
}

/**
 * Data class for SMS message data returned by the reader.
 */
data class SmsMessageData(
    val id: Long,
    val address: String,
    val date: Long,
    val preview: String,
    val body: String,
    val read: Boolean,
)