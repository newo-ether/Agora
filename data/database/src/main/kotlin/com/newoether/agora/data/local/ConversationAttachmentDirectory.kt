package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Original-file ownership survives message deletion and ends only with its conversation. */
@Entity(
    tableName = "conversation_attachment_directories",
    primaryKeys = ["conversationId", "directory"],
    indices = [Index(value = ["directory"])],
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConversationAttachmentDirectory(val conversationId: String, val directory: String)

@Dao
interface ConversationAttachmentDirectoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(claim: ConversationAttachmentDirectory)

    @Query("SELECT directory FROM conversation_attachment_directories WHERE conversationId = :conversationId ORDER BY directory")
    suspend fun directories(conversationId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_attachment_directories WHERE directory = :directory)")
    suspend fun isClaimed(directory: String): Boolean
}

/** Only imported/generated children of the managed Sandbox attachment root may be reclaimed. */
object ManagedAttachmentDirectories {
    const val SANDBOX_ROOT = "/home/agora/attachments/"
    private val directoryPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun isDirectory(value: String): Boolean = directoryPattern.matches(value)

    fun fromSandboxPath(path: String?): String? {
        if (path == null || !path.startsWith(SANDBOX_ROOT) || '\\' in path || '\u0000' in path) return null
        val parts = path.removePrefix(SANDBOX_ROOT).split('/')
        if (parts.size < 2 || !isDirectory(parts.first()) ||
            parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.first()
    }
}
