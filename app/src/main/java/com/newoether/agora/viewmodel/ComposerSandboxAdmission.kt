package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment

internal enum class SandboxSourceAdmission { IMPORT, RECOVERY, REJECTED }

/** Commits source ownership before import, or retains non-reconstructible pasted text for recovery. */
internal suspend fun ComposerOwnerSession.persistSandboxSource(
    ownerId: String,
    attachment: SelectedAttachment,
    drafts: ComposerDraftController,
    reloadOnConflict: suspend () -> Unit,
): SandboxSourceAdmission {
    val current = state.value
    val requested = durable.attachments + attachment
    val result = drafts.persist(ownerId, durable.revision, current.text, requested)
    if (!result.succeeded || !result.matchesRequested) {
        if (result.succeeded) reloadOnConflict()
        if (attachment.sourceText == null) return SandboxSourceAdmission.REJECTED
        transientAttachmentIds += attachment.localId
        publish(state.value.copy(
            attachments = state.value.attachments + attachment.copy(importState = AttachmentImportState.FAILED),
        ))
        return SandboxSourceAdmission.RECOVERY
    }
    durable = current.copy(attachments = requested, revision = result.revision)
    publish(current.copy(revision = result.revision))
    return SandboxSourceAdmission.IMPORT
}

internal fun ConversationComposerSnapshot.unsavedPastedSources(durable: ConversationComposerSnapshot) =
    attachments.filter { attachment ->
        attachment.sourceText != null && durable.attachments.none { it.localId == attachment.localId }
    }

/** Flushes recovery sources before Send so accepting other input cannot discard a failed paste. */
internal suspend fun ComposerOwnerSession.persistSubmissionSources(
    ownerId: String,
    drafts: ComposerDraftController,
    reloadOnConflict: suspend () -> Unit,
): Boolean {
    val current = state.value
    val attachments = durable.attachments + current.unsavedPastedSources(durable)
    val result = drafts.persist(ownerId, durable.revision, current.text, attachments)
    if (!result.succeeded) return false
    if (!result.matchesRequested) {
        reloadOnConflict()
        return false
    }
    durable = durable.copy(text = current.text, attachments = attachments, revision = result.revision)
    publish(current.copy(revision = result.revision))
    return true
}
