package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MaintenanceDebtEntity
import com.newoether.agora.data.local.MessageAttachmentReference

internal suspend fun ConversationRepository.enqueueReconcileDebt(): Boolean {
    var scheduled = false
    for (kind in MaintenanceDebtEntity.RECONCILE_KINDS) {
        scheduled = enqueueDebt(kind, listOf(MaintenanceDebtEntity.RECONCILE_IDENTITY)) or scheduled
    }
    return scheduled
}

internal suspend fun ConversationRepository.enqueueMessageAttachmentDebt(
    references: List<MessageAttachmentReference>,
): Boolean {
    if (references.any { reference ->
            val metadata = reference.attachmentMeta
            (metadata == null && reference.images.isNotEmpty()) ||
                (metadata != null && metadata.decodeAttachmentMeta() == null)
        }
    ) return enqueueAttachmentReconcile()
    return enqueueAttachmentDebt(references.messageReclaimablePaths())
}

internal suspend fun ConversationRepository.enqueueDebt(kind: String, identities: Collection<String>): Boolean {
    val dao = maintenanceDebtDao ?: return false
    val exact = identities.filter(String::isNotBlank).distinct()
    if (exact.isEmpty()) return false
    val at = System.currentTimeMillis()
    exact.forEach { identity -> dao.enqueue(kind, identity, at) }
    return true
}

internal suspend fun ConversationRepository.deleteMessagesWithAttachments(ids: List<String>) {
    var scheduled = false
    withSemanticTransaction(ids) {
        val messages = ids.chunked(64).flatMap { chatDao.getMessagesByIds(it) }
        claimMessageDirectories(messages)
        chatDao.deleteMessagesByIds(ids)
        scheduled = enqueueMessageAttachmentDebt(messages.map { it.toAttachmentReference() })
    }
    if (scheduled) scheduleMaintenance()
}
