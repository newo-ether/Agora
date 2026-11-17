package com.newoether.agora.viewmodel

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Text writes share the draft owner's lock, revision fence and durable attachment snapshot. */
internal suspend fun ComposerOwnerSession.persistText(
    ownerId: String,
    text: String,
    drafts: ComposerDraftController,
    replaceText: Boolean = false,
    reload: suspend () -> Unit,
): Boolean = withContext(NonCancellable) {
    mutex.withLock {
        val current = state.value
        if (replaceText) {
            if (frozenSubmissionId != null) return@withLock false
            publish(current.copy(text = text))
        } else if (current.text != text) {
            return@withLock false
        }
        val result = drafts.persist(
            conversationId = ownerId,
            expectedRevision = durable.revision,
            text = text,
            attachments = durable.attachments,
        )
        if (!result.succeeded) return@withLock false
        if (!result.matchesRequested) {
            reload()
            return@withLock false
        }
        durable = durable.copy(text = text, revision = result.revision)
        publish(state.value.copy(revision = result.revision))
        true
    }
}
