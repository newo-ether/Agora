package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Derived ownership only; source rows and the existing live-file owner remain authoritative. */
@Entity(
    tableName = "attachment_reference_owners",
    primaryKeys = ["kind", "ownerId"],
    indices = [Index(value = ["dirty", "kind", "ownerId"]), Index(value = ["invalid", "kind", "ownerId"])],
)
data class AttachmentReferenceOwner(
    val kind: Int,
    val ownerId: String,
    val token: String,
    val dirty: Boolean,
    val invalid: Boolean,
)

@Entity(
    tableName = "attachment_references",
    primaryKeys = ["kind", "ownerId", "path"],
    indices = [Index(value = ["path"])],
    foreignKeys = [ForeignKey(
        entity = AttachmentReferenceOwner::class,
        parentColumns = ["kind", "ownerId"],
        childColumns = ["kind", "ownerId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AttachmentReference(val kind: Int, val ownerId: String, val path: String)

@Entity(tableName = "attachment_reference_state")
data class AttachmentReferenceState(
    @PrimaryKey val id: Int = 0,
    val phase: Int = 0,
    val afterId: String? = null,
    val revision: Long = 0,
)
