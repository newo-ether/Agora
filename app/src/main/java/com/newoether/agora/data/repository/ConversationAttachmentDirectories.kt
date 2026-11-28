package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ConversationAttachmentDirectory
import com.newoether.agora.data.local.ManagedAttachmentDirectories
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.sandboxGeneratedOutputImages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Called inside the existing graph/admission transaction, never by an acceptance callback. */
internal suspend fun ConversationRepository.claimMessageDirectories(messages: List<MessageEntity>) {
    for (message in messages) claimAttachmentDirectories(
        message.conversationId, message.attachmentMeta, message.toolCallJson,
    )
}

internal suspend fun ConversationRepository.claimAttachmentDirectories(
    conversationId: String,
    metadata: String?,
    toolCallJson: String? = null,
) {
    val ledger = database?.conversationAttachmentDirectoryDao() ?: return
    val directories = metadata.decodeAttachmentMeta()?.items.orEmpty()
        .mapNotNull { ManagedAttachmentDirectories.fromSandboxPath(it.sandboxPath) }.toMutableSet()
    // Reuse the generated-image descriptor decoder; never infer ownership from arbitrary text.
    val segments = toolCallJson?.let {
        runCatching { Json.decodeFromString<List<MessageSegment>>(it) }.getOrNull()
    }.orEmpty()
    for (segment in segments) {
        sandboxGeneratedOutputImages(segment.toolName, segment.toolResult).forEach { image ->
            ManagedAttachmentDirectories.fromSandboxPath((image["path"] as? JsonPrimitive)?.content)
                ?.let(directories::add)
        }
    }
    for (directory in directories) ledger.insert(ConversationAttachmentDirectory(conversationId, directory))
}

internal suspend fun ConversationRepository.claimLegacyDirectories(
    conversationId: String,
    references: List<MessageAttachmentReference>,
) {
    for (reference in references) claimAttachmentDirectories(
        conversationId, reference.attachmentMeta, reference.toolCallJson,
    )
}

/** Pending queue text stays memory-owned, but accepted originals must survive queue disposal. */
suspend fun ConversationRepository.claimQueuedAttachmentDirectories(
    conversationId: String,
    attachments: List<SelectedAttachment>,
) {
    val directories = attachments.mapNotNull {
        ManagedAttachmentDirectories.fromSandboxPath(it.sandboxPath)
    }.distinct()
    if (directories.isEmpty()) return
    val ledger = database?.conversationAttachmentDirectoryDao() ?: return
    withProviderContextSnapshot {
        check(chatDao.getConversationDraftAttachmentReference(conversationId) != null) {
            "Queued attachment conversation no longer exists"
        }
        directories.forEach { ledger.insert(ConversationAttachmentDirectory(conversationId, it)) }
    }
}
