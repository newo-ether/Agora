package com.newoether.agora.data.repository

internal suspend fun ConversationRepository.deleteConversationWithChildren(id: String) {
    prepareConversationDeletion(id)
    val scheduled = withSemanticTransaction(updatedAt = System.currentTimeMillis()) {
        var scheduled = false
        for (ownedId in chatDao.getSubagentIds(id) + id) {
            val owner = chatDao.getConversationDraftAttachmentReference(ownedId) ?: continue
            var afterId: String? = null
            while (true) {
                val page = chatDao.getConversationMessageAttachmentReferencesPage(ownedId, afterId, 128)
                scheduled = enqueueMessageAttachmentDebt(page) or scheduled
                if (page.size < 128) break
                afterId = page.last().id
            }
            val raw = owner.draftAttachments
            val draft = raw.decodeSelectedAttachments()
            scheduled = scheduled or when {
                raw != null && draft == null -> enqueueAttachmentReconcile()
                else -> enqueueAttachmentDebt(draft.orEmpty().reclaimablePaths())
            }
            // The canonical deletion removes vectors once and cascades messages and derived rows.
            chatDao.deleteConversation(ownedId)
        }
        scheduled
    }
    if (scheduled) scheduleMaintenance()
}
