package com.newoether.agora.remote

import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.flow.MutableStateFlow

/** Ordinary sends and artifact follow-ups accept the same exact-owner native receipt. */
internal fun confirmRemoteDelivery(
    state: MutableStateFlow<RemoteState>, attachmentStore: RemoteAttachmentStore?,
    scrollRequests: ScrollRequestCoordinator, owner: String, attempt: RemoteAttempt,
    fresh: List<RemoteMessageNode>, receipts: List<RemoteInputReceipt> = emptyList(),
) {
    val snapshot = state.value
    val current = snapshot.attempts[owner]?.takeIf { it.clientId == attempt.clientId } ?: return
    if (snapshot.owner != owner || current.delivery == RemoteDelivery.DELIVERED) return
    val message = fresh.firstOrNull { it.role == "user" && it.clientId == current.clientId &&
        (current.turnId == null || current.turnId == it.turnId) }
    val receipt = receipts.firstOrNull { it.clientId == current.clientId &&
        it.turnId.isNotBlank() && it.messageId.isNotBlank() && (current.turnId == null || current.turnId == it.turnId) }
    if (message == null && receipt == null) return
    if (!current.preserveComposer) snapshot.attachments[owner].orEmpty().forEach { attachmentStore?.remove(it) }
    state.value = snapshot.copy(
        attachments = if (current.preserveComposer) snapshot.attachments else snapshot.attachments - owner,
        drafts = if (!current.preserveComposer && snapshot.drafts[owner] == current.text) snapshot.drafts - owner else snapshot.drafts,
        attempts = snapshot.attempts + (owner to current.copy(delivery = RemoteDelivery.DELIVERED)),
    )
    val target = message?.let { it.nativeId ?: it.id } ?: snapshot.messageGroups.lastOrNull()?.stub?.id
    if (target != null) scrollRequests.requestAbsoluteBottomAfter(owner, target)
}
