package com.newoether.agora.webui

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.decodeSelectedAttachments
import com.newoether.agora.data.repository.removedReclaimablePaths
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.viewmodel.ComposerDraftPersistence
import com.newoether.agora.viewmodel.ConversationWorkspaceDraft
import java.util.concurrent.ConcurrentHashMap

/** Browser drafts: kept for the connection only, never written to the phone's Room drafts. */
internal class SessionDraftPersistence(private val conversations: ConversationRepository) : ComposerDraftPersistence {
    private val drafts = ConcurrentHashMap<String, ConversationWorkspaceDraft>()
    fun ownerIds(): List<String> = drafts.keys.toList()
    fun clear() {
        AttachmentFiles.releaseLivePaths(this)
        drafts.clear()
    }

    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft =
        drafts[ownerId] ?: ConversationWorkspaceDraft(text = "", attachmentsJson = null)

    override suspend fun updateDraft(ownerId: String, text: String, attachmentsJson: String?) {
        // The canonical sweeper retains debt while another live owner still protects the path.
        val previous = drafts[ownerId]?.attachmentsJson.decodeSelectedAttachments().orEmpty()
        val removed = previous.removedReclaimablePaths(attachmentsJson.decodeSelectedAttachments())
        if (removed.isNotEmpty()) conversations.deleteUnreferencedDraftAttachmentFiles(
            removed.map { SelectedAttachment(uri = it, type = "file", localPath = it) },
        )
        drafts[ownerId] = ConversationWorkspaceDraft(text, attachmentsJson)
        retainDraftFiles()
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        clearAcceptedDraft(ownerId, reclaimAttachments = true)
    }
    override suspend fun clearAcceptedDraft(ownerId: String, reclaimAttachments: Boolean) {
        if (reclaimAttachments) {
            conversations.deleteUnreferencedDraftAttachmentFiles(
                drafts[ownerId]?.attachmentsJson.decodeSelectedAttachments().orEmpty(),
            )
        }
        drafts.remove(ownerId)
        retainDraftFiles()
    }
    private fun retainDraftFiles() {
        val attachments = drafts.values.flatMap { it.attachmentsJson.decodeSelectedAttachments().orEmpty() }
        AttachmentFiles.setLivePaths(this, AttachmentFiles.ownedPaths(attachments))
    }
}
