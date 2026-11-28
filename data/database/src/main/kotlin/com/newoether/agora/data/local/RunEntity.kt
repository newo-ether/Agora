package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus

@Entity(
    tableName = "runs",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentRunId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["parentRunId"]),
        // SQLite permits multiple nulls. ACTIVE/STOPPING use slot 1; terminal Runs use null.
        Index(value = ["conversationId", "activeSlot"], unique = true),
    ],
)
data class RunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentRunId: String?,
    val status: RunStatus,
    val activeSlot: Int?,
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val stopRequestedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: RunEndReason? = null,
    val currentPass: Int = 0,
    val legacyAmbiguous: Boolean = false,
) {
    init {
        require(currentPass >= 0)
        require((activeSlot == 1) == !status.isTerminal)
        require(status.isTerminal == (endedAt != null && endReason != null))
        require(status != RunStatus.STOPPING || stopRequestedAt != null)
    }
}
