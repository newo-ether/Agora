package com.newoether.agora.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.Fts4
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.flow.Flow
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
}

@Entity(tableName = "conversations")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val selectedBranchesJson: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null
)

@Entity(
    tableName = "embeddings",
    indices = [Index(value = ["messageId", "modelId"], unique = true)]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val modelId: String,
    val embedding: ByteArray,
    val chunkText: String,
    val dimension: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id && messageId == other.messageId && modelId == other.modelId
            && embedding.contentEquals(other.embedding) && chunkText == other.chunkText && dimension == other.dimension
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + dimension
        return result
    }
}

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
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
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCallJson: String? = null,
    val attachmentMeta: String? = null
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY lastUpdated DESC")
    fun getAllConversations(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ChatEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteEmbeddingsByConversation(conversationId: String)

    @Query("DELETE FROM embeddings WHERE NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)")
    suspend fun deleteOrphanedEmbeddings()

    @Query("SELECT m.* FROM messages m INNER JOIN messages_fts f ON m.id = f.messageId WHERE messages_fts MATCH :query AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' ORDER BY m.timestamp DESC LIMIT :limit")
    suspend fun searchMessagesFts(query: String, limit: Int): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE (m.text LIKE '%' || :query || '%' OR c.title LIKE '%' || :query || '%') AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' ORDER BY m.timestamp DESC LIMIT :limit")
    suspend fun searchMessagesLike(query: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    // Embeddings
    @Upsert
    suspend fun upsertEmbedding(embedding: EmbeddingEntity)

    @Query("SELECT * FROM embeddings WHERE messageId = :messageId LIMIT 1")
    suspend fun getEmbedding(messageId: String): EmbeddingEntity?

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE messageId = :messageId")
    suspend fun deleteEmbedding(messageId: String)

    @Query("SELECT * FROM embeddings WHERE modelId = :modelId")
    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteEmbeddingsByModel(modelId: String)

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelId = :modelId")
    suspend fun getEmbeddingCountByModel(modelId: String): Int

    @Query("SELECT messageId FROM embeddings WHERE modelId = :modelId")
    suspend fun getEmbeddedMessageIdsByModel(modelId: String): List<String>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getAllMessagesForIndexing(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getIndexableMessageCount(): Int

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>

    // Bulk export/import
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsList(): List<ChatEntity>

    @Query("SELECT * FROM messages")
    suspend fun getAllMessagesList(): List<MessageEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun findExistingMessageIds(ids: List<String>): List<String>
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class, EmbeddingEntity::class, MessageFtsEntity::class],
    version = ChatDatabase.CURRENT_VERSION,
    exportSchema = true
)@TypeConverters(MessageConverters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val CURRENT_VERSION = 14
        const val DB_NAME = "agora_db"

        val ALL_MIGRATIONS = listOf(
            // v1 → v2 added messages.images (List<String> stored as TEXT via converter,
            // NOT NULL with "" representing an empty list). This step was missing, so any
            // device still on schema v1 crashed on launch with "migration 1 to 2 not found".
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN selectedBranchesJson TEXT")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTimeMs INTEGER")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
                }
            },
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN systemPromptId TEXT")
                }
            },
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")
                }
            },
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTitle TEXT")
                }
            },
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN toolCallJson TEXT")
                }
            },
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS embeddings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            messageId TEXT NOT NULL,
                            embedding BLOB NOT NULL,
                            chunkText TEXT NOT NULL,
                            dimension INTEGER NOT NULL
                        )
                    """)
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId ON embeddings (messageId)")
                }
            },
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE embeddings ADD COLUMN modelId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("DROP INDEX IF EXISTS index_embeddings_messageId")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId_modelId ON embeddings (messageId, modelId)")
                }
            },
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMeta TEXT")
                }
            },
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(messageId, text, conversationTitle)")
                    db.execSQL("INSERT INTO messages_fts(messageId, text, conversationTitle) SELECT m.id, m.text, c.title FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_insert AFTER INSERT ON messages FOR EACH ROW BEGIN INSERT INTO messages_fts(messageId, text, conversationTitle) VALUES (new.id, new.text, (SELECT title FROM conversations WHERE id = new.conversationId)); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_update AFTER UPDATE OF text ON messages FOR EACH ROW BEGIN UPDATE messages_fts SET text = new.text WHERE messageId = new.id; END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_conversations_update AFTER UPDATE OF title ON conversations FOR EACH ROW BEGIN UPDATE messages_fts SET conversationTitle = new.title WHERE messageId IN (SELECT id FROM messages WHERE conversationId = new.id); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_delete AFTER DELETE ON messages FOR EACH ROW BEGIN DELETE FROM messages_fts WHERE messageId = old.id; END")
                }
            },
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS messages_fts")
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(messageId, text, conversationTitle)")
                    db.execSQL("INSERT INTO messages_fts(messageId, text, conversationTitle) SELECT m.id, m.text, c.title FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
                    db.execSQL("DROP TRIGGER IF EXISTS tr_messages_insert")
                    db.execSQL("DROP TRIGGER IF EXISTS tr_messages_update")
                    db.execSQL("DROP TRIGGER IF EXISTS tr_conversations_update")
                    db.execSQL("DROP TRIGGER IF EXISTS tr_messages_delete")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_insert AFTER INSERT ON messages FOR EACH ROW BEGIN INSERT INTO messages_fts(messageId, text, conversationTitle) VALUES (new.id, new.text, (SELECT title FROM conversations WHERE id = new.conversationId)); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_update AFTER UPDATE OF text ON messages FOR EACH ROW BEGIN UPDATE messages_fts SET text = new.text WHERE messageId = new.id; END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_conversations_update AFTER UPDATE OF title ON conversations FOR EACH ROW BEGIN UPDATE messages_fts SET conversationTitle = new.title WHERE messageId IN (SELECT id FROM messages WHERE conversationId = new.id); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_delete AFTER DELETE ON messages FOR EACH ROW BEGIN DELETE FROM messages_fts WHERE messageId = old.id; END")
                }
            }
        )

        fun getStoredVersion(context: Context): Int {
            val dbPath = context.getDatabasePath(DB_NAME)
            if (!dbPath.exists()) return 0
            return try {
                val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
                val version = db.version
                db.close()
                version
            } catch (e: Exception) {
                0
            }
        }

        fun build(context: Context): ChatDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                DB_NAME
            ).addMigrations(*ALL_MIGRATIONS.toTypedArray())
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_insert AFTER INSERT ON messages FOR EACH ROW BEGIN INSERT INTO messages_fts(messageId, text, conversationTitle) VALUES (new.id, new.text, (SELECT title FROM conversations WHERE id = new.conversationId)); END")
                        db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_update AFTER UPDATE OF text ON messages FOR EACH ROW BEGIN UPDATE messages_fts SET text = new.text WHERE messageId = new.id; END")
                        db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_conversations_update AFTER UPDATE OF title ON conversations FOR EACH ROW BEGIN UPDATE messages_fts SET conversationTitle = new.title WHERE messageId IN (SELECT id FROM messages WHERE conversationId = new.id); END")
                        db.execSQL("CREATE TRIGGER IF NOT EXISTS tr_messages_delete AFTER DELETE ON messages FOR EACH ROW BEGIN DELETE FROM messages_fts WHERE messageId = old.id; END")
                    }
                })
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}

@Fts4
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @PrimaryKey val rowid: Int = 0,
    val messageId: String,
    val text: String,
    val conversationTitle: String
)
