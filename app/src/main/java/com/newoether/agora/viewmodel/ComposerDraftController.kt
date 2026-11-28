package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LoadedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

data class DraftPersistResult(
    val revision: Long,
    val succeeded: Boolean,
    val matchesRequested: Boolean,
)

data class DraftClearResult(
    val attachments: List<SelectedAttachment>,
    val revision: Long,
    val succeeded: Boolean,
    val remainingText: String = "",
    val remainingAttachments: List<SelectedAttachment> = emptyList(),
)

private data class PersistedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

private class RepositoryComposerDraftPersistence(
    private val conversations: ConversationRepository,
) : ComposerDraftPersistence {
    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft {
        val entity = conversations.getConversation(ownerId)
        return ConversationWorkspaceDraft(
            text = entity?.draftText.orEmpty(),
            attachmentsJson = entity?.draftAttachments,
        )
    }

    override suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    ) {
        conversations.updateDraft(ownerId, text, attachmentsJson)
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        clearAcceptedDraft(ownerId, reclaimAttachments = true)
    }

    override suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
    ) {
        conversations.updateDraft(
            conversationId = ownerId,
            draftText = "",
            draftAttachments = null,
            reclaimRemovedAttachments = reclaimAttachments,
        )
    }
}

/**
 * Owns the serialized, revision-checked composer-draft cache and its durable projection.
 *
 * This controller has no generation or UI lifecycle authority. Its accepted-clear result only
 * reports attachments whose draft ownership was durably removed; the caller decides whether the
 * accepted input has another owner before asking the repository to reclaim them.
 */
internal class ComposerDraftController(
    private val persistence: ComposerDraftPersistence,
    private val conversations: ConversationRepository,
    private val hydrateAttachment: suspend (SelectedAttachment) -> SelectedAttachment = { it },
) {
    constructor(
        conversations: ConversationRepository,
        hydrateAttachment: suspend (SelectedAttachment) -> SelectedAttachment = { it },
    ) : this(
        persistence = RepositoryComposerDraftPersistence(conversations),
        conversations = conversations,
        hydrateAttachment = hydrateAttachment,
    )
    private val persistenceMutex = Mutex()
    private val persistedDrafts = mutableMapOf<String, PersistedComposerDraft>()

    /**
     * Persists one revision-checked composer snapshot. Once a write starts it is atomic with
     * respect to cancellation; newer UI snapshots wait behind the mutex instead of overtaking it.
     */
    suspend fun persist(
        conversationId: String,
        expectedRevision: Long,
        text: String,
        attachments: List<SelectedAttachment>,
        explicitlyRemovedAttachments: List<SelectedAttachment> = emptyList(),
    ): DraftPersistResult = withContext(Dispatchers.IO + NonCancellable) {
        persistenceMutex.withLock {
            val current = try {
                persistedDrafts[conversationId]
                    ?: read(conversationId).also {
                        persistedDrafts[conversationId] = it
                    }
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to read draft for $conversationId", e)
                return@withLock DraftPersistResult(
                    revision = persistedDrafts[conversationId]?.revision ?: expectedRevision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
            if (current.revision != expectedRevision) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = current.text == text && current.attachments == attachments,
                )
            }

            if (current.text == text && current.attachments == attachments) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            }

            try {
                val json = if (attachments.isEmpty()) null else Json.encodeToString(attachments)
                persistence.updateDraft(conversationId, text, json)
                val next = PersistedComposerDraft(
                    text = text,
                    attachments = attachments,
                    revision = current.revision + 1L,
                )
                persistedDrafts[conversationId] = next
                // updateDraft already enqueues paths removed from durable ownership.
                reclaimAttachments(explicitlyRemovedAttachments)
                DraftPersistResult(
                    revision = next.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to persist draft for $conversationId", e)
                DraftPersistResult(
                    revision = current.revision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
        }
    }

    /**
     * A successfully accepted send invalidates every older UI tail-flush by advancing the cached
     * revision only after the draft reference is durably cleared.
     */
    suspend fun clearAccepted(
        conversationId: String,
        reclaimAttachments: Boolean = true,
        acceptedRevision: Long? = null,
        acceptedText: String? = null,
        acceptedAttachmentIds: Set<String>? = null,
        expectedWorkspace: com.newoether.agora.data.local.NewChatPersistEntity? = null,
    ): DraftClearResult =
        withContext(Dispatchers.IO + NonCancellable) {
            persistenceMutex.withLock {
                try {
                    val current = persistedDrafts[conversationId] ?: read(conversationId)
                    val preserveNewerText =
                        acceptedRevision != null &&
                            current.revision != acceptedRevision &&
                            current.text != acceptedText
                    val remainingText = current.text.takeIf { preserveNewerText }.orEmpty()
                    val removedAttachments = if (acceptedAttachmentIds == null) {
                        current.attachments
                    } else {
                        current.attachments.filter { it.localId in acceptedAttachmentIds }
                    }
                    val remainingAttachments = if (acceptedAttachmentIds == null) {
                        emptyList()
                    } else {
                        current.attachments.filterNot { it.localId in acceptedAttachmentIds }
                    }
                    val attachmentsJson = remainingAttachments
                        .takeIf(List<*>::isNotEmpty)
                        ?.let { Json.encodeToString(it) }
                    if (
                        remainingText != current.text ||
                        remainingAttachments != current.attachments
                    ) {
                        if (remainingText.isEmpty() && remainingAttachments.isEmpty()) {
                            persistence.clearAcceptedDraft(
                                ownerId = conversationId,
                                reclaimAttachments = reclaimAttachments,
                                expectedWorkspace = expectedWorkspace,
                            )
                        } else {
                            persistence.updateDraft(conversationId, remainingText, attachmentsJson)
                        }
                    }
                    val revision = current.revision + 1L
                    persistedDrafts[conversationId] = PersistedComposerDraft(
                        text = remainingText,
                        attachments = remainingAttachments,
                        revision = revision,
                    )
                    DraftClearResult(
                        attachments = removedAttachments,
                        revision = revision,
                        succeeded = true,
                        remainingText = remainingText,
                        remainingAttachments = remainingAttachments,
                    )
                } catch (e: Exception) {
                    DebugLog.e(
                        "ChatViewModel",
                        "Failed to clear accepted draft for $conversationId",
                        e,
                    )
                    val current = persistedDrafts[conversationId]
                    DraftClearResult(
                        attachments = emptyList(),
                        revision = current?.revision ?: 0L,
                        succeeded = false,
                        remainingText = current?.text.orEmpty(),
                        remainingAttachments = current?.attachments.orEmpty(),
                    )
                }
            }
        }

    /** Loads and revision-tags the stored draft under the same serialization boundary as writes. */
    suspend fun load(conversationId: String): LoadedComposerDraft = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            val loaded = read(conversationId)
            persistedDrafts[conversationId] = loaded
            LoadedComposerDraft(
                text = loaded.text,
                attachments = loaded.attachments,
                revision = loaded.revision,
            )
        }
    }

    /** Releases this owner's process-local cache without changing durable draft state. */
    suspend fun evictCached(conversationId: String): Unit = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            persistedDrafts.remove(conversationId)
            Unit
        }
    }

    private suspend fun read(conversationId: String): PersistedComposerDraft {
        val priorRevision = persistedDrafts[conversationId]?.revision ?: 0L
        val draft = persistence.loadDraft(conversationId)
        val decoded = try {
            decodeAttachments(draft.attachmentsJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(
                "ChatViewModel",
                "Failed to deserialize draft attachments for $conversationId",
                e,
            )
            DecodedAttachments(emptyList(), migrated = false)
        }
        var revision = priorRevision
        if (decoded.migrated) {
            try {
                persistence.updateDraft(
                    ownerId = conversationId,
                    text = draft.text,
                    attachmentsJson = Json.encodeToString(decoded.attachments),
                )
                revision += 1L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the normalized attachment visible and retryable in memory. A later load
                // or composer change can retry the durable metadata write.
                DebugLog.w(
                    "ChatViewModel",
                    "Failed to persist legacy attachment upgrade for $conversationId",
                    e,
                )
            }
        }
        return PersistedComposerDraft(
            text = draft.text,
            attachments = decoded.attachments,
            revision = revision,
        )
    }

    private data class DecodedAttachments(
        val attachments: List<SelectedAttachment>,
        val migrated: Boolean,
    )

    /**
     * Old drafts predate importState and canonical private artifacts. Serialization defaults would
     * otherwise turn those records into incomplete READY attachments that Send silently omits.
     */
    private suspend fun decodeAttachments(raw: String?): DecodedAttachments {
        if (raw == null) return DecodedAttachments(emptyList(), migrated = false)
        val elements = Json.parseToJsonElement(raw) as? JsonArray
            ?: error("Draft attachments must be a JSON array")
        var migrated = false
        val attachments = elements.map { element ->
            val attachment = Json.decodeFromJsonElement(
                SelectedAttachment.serializer(),
                element,
            )
            val hydrated = hydrateAttachment(attachment)
            val objectValue = element as? JsonObject
            val normalized = if (
                objectValue?.containsKey("importState") != false ||
                hydrated.unavailable || hydrated.hasCanonicalReadyArtifact()
            ) {
                hydrated
            } else {
                hydrated.copy(importState = AttachmentImportState.PROCESSING)
            }
            if (normalized != attachment) migrated = true
            normalized
        }
        return DecodedAttachments(attachments, migrated)
    }

    suspend fun reclaimAttachments(attachments: List<SelectedAttachment>) {
        if (attachments.isEmpty()) return
        try {
            conversations.deleteUnreferencedDraftAttachmentFiles(attachments)
        } catch (e: Exception) {
            // The durable reference update already succeeded. A cleanup failure may leak a private
            // file, but must never roll the draft back to a now-invalid attachment.
            DebugLog.w("ChatViewModel", "Failed to reclaim draft attachment files", e)
        }
    }
}
